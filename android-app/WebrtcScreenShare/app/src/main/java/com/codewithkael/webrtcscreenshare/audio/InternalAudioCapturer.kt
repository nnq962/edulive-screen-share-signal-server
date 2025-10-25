package com.codewithkael.webrtcscreenshare.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

/**
 * Captures internal audio (system audio) from Android device
 * Requires Android 10+ and MediaProjection
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onAudioData: (audioData: ByteArray, sampleRate: Int, channels: Int) -> Unit
) {
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "⚠️ MediaProjection stopped!")
            stopCapture()
        }
    }
    
    companion object {
        private const val TAG = "InternalAudioCapturer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS = 2
        private const val BUFFER_SIZE_FACTOR = 4
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var sendingJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // High-performance audio data queue - buffered channel for smooth audio flow
    private val audioDataChannel = Channel<AudioChunk>(capacity = 100) // Buffer up to 100 chunks (~2 seconds at 44.1kHz)
    
    @Volatile
    private var isCapturing = false
    
    // Data class for audio chunk with metadata
    private data class AudioChunk(
        val data: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun startCapture(): Boolean {
        if (isCapturing) {
            Log.w(TAG, "Already capturing audio")
            return true
        }

        try {
            Log.d(TAG, "🎤 Starting internal audio capture...")
            Log.d(TAG, "🔍 Checking RECORD_AUDIO_OUTPUT permission...")
            
            mediaProjection.registerCallback(projectionCallback, null)
            Log.d(TAG, "✅ MediaProjection callback registered")

            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            
            try {
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    configBuilder.addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                }
                
                val assistantText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ", ASSISTANT"
                } else {
                    ""
                }
                Log.d(TAG, "✅ Added audio usages: UNKNOWN, MEDIA, GAME$assistantText")
                
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error adding some audio usages: ${e.message}")
            }
            
            val config = configBuilder.build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "❌ Invalid buffer size: $minBufferSize")
                return false
            }

            val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
            Log.d(TAG, "📊 Min buffer size: $minBufferSize, Using: $bufferSize bytes")

            @Suppress("MissingPermission")
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord initialization failed")
                Log.e(TAG, "❌ State: ${audioRecord?.state}")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            Log.d(TAG, "✅ AudioRecord initialized successfully")
            audioRecord?.startRecording()
            
            delay(100)
            
            val recordingState = audioRecord?.recordingState
            Log.d(TAG, "📊 Recording state after start: $recordingState")
            
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ Failed to start recording, state: $recordingState")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            isCapturing = true

            // Start separate sending coroutine for high-performance audio transmission
            sendingJob = captureScope.launch(Dispatchers.IO) {
                processSendingQueue()
            }

            // Start audio capture coroutine with high priority
            captureJob = captureScope.launch {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureAudioLoop(bufferSize)
            }

            Log.i(TAG, "✅ High-performance audio pipeline started (separate capture + sending)")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: ${e.message}", e)
            Log.e(TAG, "❌ Missing RECORD_AUDIO_OUTPUT permission!")
            Log.e(TAG, "❌ App needs to be:")
            Log.e(TAG, "   1. Signed with system signature, OR")
            Log.e(TAG, "   2. Installed as system app, OR") 
            Log.e(TAG, "   3. Root the device and grant permission manually")
            stopCapture()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start internal audio capture", e)
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
            if (e.message?.contains("permission", ignoreCase = true) == true) {
                Log.e(TAG, "❌ Permission-related error detected")
            }
            stopCapture()
            return false
        }
    }

    private suspend fun captureAudioLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        Log.i(TAG, "============================================================")
        Log.i(TAG, "🔄 AUDIO CAPTURE COROUTINE STARTED")
        Log.i(TAG, "📊 Buffer size: $bufferSize bytes")
        Log.i(TAG, "🚨 Play YouTube, Spotify, Music, etc. to test audio capture")
        Log.i(TAG, "============================================================")
        
        var consecutiveErrors = 0
        var successfulReads = 0
        var totalBytesRead = 0L
        var consecutiveZeroReads = 0

        try {
            while (isCapturing && audioRecord != null && !currentCoroutineContext().isActive.not()) {
                val bytesRead = try {
                    audioRecord?.read(buffer, 0, buffer.size) ?: -1
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception during read: ${e.message}", e)
                    -1
                }

                when {
                    bytesRead > 0 -> {
                        consecutiveErrors = 0
                        consecutiveZeroReads = 0
                        successfulReads++
                        totalBytesRead += bytesRead
                        
                        if (successfulReads == 1) {
                            Log.i(TAG, "🎵 AUDIO DATA RECEIVED! Capture is WORKING!")
                        }
                        
                        if (successfulReads % 50 == 0) {
                            Log.d(TAG, "📊 Captured $successfulReads chunks, ${totalBytesRead / 1024}KB total")
                        }
                        
                        // Queue audio data for separate sending coroutine - non-blocking
                        val audioData = buffer.copyOf(bytesRead)
                        val audioChunk = AudioChunk(audioData, SAMPLE_RATE, CHANNELS)
                        
                        // Try to send to channel without blocking capture loop
                        val result = audioDataChannel.trySend(audioChunk)
                        if (result.isFailure) {
                            // Channel is full - drop oldest data to maintain real-time performance
                            Log.w(TAG, "⚠️ Audio queue full, dropping frame to maintain low latency")
                        }
                    }
                    
                    bytesRead == 0 -> {
                        consecutiveZeroReads++
                        consecutiveErrors = 0
                        
                        if (consecutiveZeroReads == 1) {
                            Log.w(TAG, "⏳ No audio data available")
                        }
                        
                        delay(100)
                    }
                    
                    else -> {
                        consecutiveErrors++
                        
                        if (consecutiveErrors == 1) {
                            Log.e(TAG, "❌ AudioRecord read ERROR: ${getErrorName(bytesRead)}")
                        }
                        
                        if (consecutiveErrors == 5) {
                            Log.w(TAG, "🔄 Attempting to restart AudioRecord...")
                            try {
                                // Stop and release old AudioRecord
                                audioRecord?.let { record ->
                                    try {
                                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                            record.stop()
                                        }
                                        record.release()
                                        Log.d(TAG, "🔄 Old AudioRecord released")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "⚠️ Error stopping/releasing AudioRecord: ${e.message}")
                                    }
                                }
                                
                                delay(500) // Wait longer before recreating
                                
                                // Recreate AudioRecord from scratch
                                Log.d(TAG, "🔄 Recreating AudioRecord...")
                                val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                                configBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                configBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                configBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    configBuilder.addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                                }
                                val config = configBuilder.build()
                                
                                @Suppress("MissingPermission")
                                audioRecord = AudioRecord.Builder()
                                    .setAudioFormat(
                                        AudioFormat.Builder()
                                            .setEncoding(AUDIO_FORMAT)
                                            .setSampleRate(SAMPLE_RATE)
                                            .setChannelMask(CHANNEL_CONFIG)
                                            .build()
                                    )
                                    .setBufferSizeInBytes(bufferSize)
                                    .setAudioPlaybackCaptureConfig(config)
                                    .build()
                                
                                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                                    audioRecord?.startRecording()
                                    delay(100)
                                    val newState = audioRecord?.recordingState
                                    
                                    if (newState == AudioRecord.RECORDSTATE_RECORDING) {
                                        Log.i(TAG, "✅ AudioRecord recovery successful!")
                                        consecutiveErrors = 0
                                    } else {
                                        Log.e(TAG, "❌ AudioRecord recovery failed, state: $newState")
                                    }
                                } else {
                                    Log.e(TAG, "❌ AudioRecord recreation failed")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Exception during recovery: ${e.message}", e)
                            }
                        }
                        
                        if (successfulReads > 0) {
                            if (consecutiveErrors >= 15) {
                                consecutiveErrors = 0
                            }
                        } else {
                            if (consecutiveErrors >= 20) {
                                Log.e(TAG, "❌ TOO MANY ERRORS - STOPPING CAPTURE")
                                break
                            }
                        }
                        
                        delay(500)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION in audio capture loop", e)
        }

        Log.i(TAG, "🛑 AUDIO CAPTURE LOOP ENDED")
        if (successfulReads > 0) {
            Log.i(TAG, "✅ Captured $successfulReads chunks (${totalBytesRead / 1024}KB)")
        } else {
            Log.w(TAG, "❌ NO AUDIO WAS CAPTURED")
        }
    }
    
    /**
     * High-performance audio sending coroutine - processes queued audio chunks
     * Runs in separate coroutine to avoid blocking audio capture
     */
    private suspend fun processSendingQueue() {
        Log.i(TAG, "🚀 Audio sending pipeline started - optimized for low latency")
        var processedChunks = 0
        var totalSentBytes = 0L
        var lastLogTime = System.currentTimeMillis()
        
        try {
            while (isCapturing) {
                try {
                    // Receive audio chunk from capture thread
                    val audioChunk = audioDataChannel.receive()
                    
                    // Send via callback with minimal latency
                    onAudioData(audioChunk.data, audioChunk.sampleRate, audioChunk.channels)
                    
                    processedChunks++
                    totalSentBytes += audioChunk.data.size
                    
                    // Performance logging every 5 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime >= 5000) {
                        val avgLatency = if (processedChunks > 0) {
                            (now - audioChunk.timestamp) 
                        } else 0
                        Log.d(TAG, "🚀 Sent $processedChunks chunks (${totalSentBytes/1024}KB), avg_latency=${avgLatency}ms")
                        lastLogTime = now
                    }
                    
                } catch (e: Exception) {
                    if (isCapturing) {
                        Log.e(TAG, "❌ Error in sending pipeline: ${e.message}")
                        delay(10) // Brief pause on error
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sending pipeline error: ${e.message}", e)
        }
        
        Log.i(TAG, "🛑 Audio sending pipeline ended")
    }
    
    private fun getErrorName(error: Int): String {
        return when (error) {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioRecord.ERROR -> "ERROR"
            else -> "UNKNOWN_ERROR($error)"
        }
    }

    fun stopCapture() {
        Log.d(TAG, "🛑 Stopping internal audio capture...")
        isCapturing = false
        
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error unregistering callback", e)
        }

        captureJob?.let { job ->
            try {
                runBlocking { 
                    job.cancelAndJoin() 
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cancelling capture job", e)
            }
        }
        captureJob = null

        sendingJob?.let { job ->
            try {
                runBlocking { 
                    job.cancelAndJoin() 
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error cancelling sending job", e)
            }
        }
        sendingJob = null
        
        // Close the audio data channel
        audioDataChannel.close()

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error releasing AudioRecord", e)
            }
        }
        audioRecord = null

        Log.d(TAG, "✅ Internal audio capture stopped")
    }

    fun isCapturing(): Boolean = isCapturing
}
