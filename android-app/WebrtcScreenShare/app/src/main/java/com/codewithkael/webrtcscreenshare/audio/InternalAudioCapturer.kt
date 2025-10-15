package com.codewithkael.webrtcscreenshare.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Captures internal audio (system audio) from Android device
 * Requires Android 10+ and MediaProjection
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val onAudioData: (audioData: String, sampleRate: Int, channels: Int) -> Unit
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
    private var captureThread: Thread? = null
    
    @Volatile
    private var isCapturing = false

    fun startCapture(): Boolean {
        if (isCapturing) {
            Log.w(TAG, "Already capturing audio")
            return true
        }

        try {
            Log.d(TAG, "🎤 Starting internal audio capture...")
            
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
            
            Thread.sleep(100)
            
            val recordingState = audioRecord?.recordingState
            Log.d(TAG, "📊 Recording state after start: $recordingState")
            
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ Failed to start recording, state: $recordingState")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            isCapturing = true

            captureThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureAudioLoop(bufferSize)
            }.apply {
                name = "InternalAudioCaptureThread"
                start()
            }

            Log.d(TAG, "✅ Internal audio capture started successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start internal audio capture", e)
            stopCapture()
            return false
        }
    }

    private fun captureAudioLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        Log.i(TAG, "============================================================")
        Log.i(TAG, "🔄 AUDIO CAPTURE LOOP STARTED")
        Log.i(TAG, "📊 Buffer size: $bufferSize bytes")
        Log.i(TAG, "🚨 Play YouTube, Spotify, Music, etc. to test audio capture")
        Log.i(TAG, "============================================================")
        
        var consecutiveErrors = 0
        var successfulReads = 0
        var totalBytesRead = 0L
        var consecutiveZeroReads = 0

        try {
            while (isCapturing && audioRecord != null) {
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
                        
                        val base64Data = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)
                        
                        try {
                            onAudioData(base64Data, SAMPLE_RATE, CHANNELS)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Exception in onAudioData callback: ${e.message}", e)
                        }
                    }
                    
                    bytesRead == 0 -> {
                        consecutiveZeroReads++
                        consecutiveErrors = 0
                        
                        if (consecutiveZeroReads == 1) {
                            Log.w(TAG, "⏳ No audio data available")
                        }
                        
                        Thread.sleep(100)
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
                                
                                Thread.sleep(1000) // Wait longer before recreating
                                
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
                                    Thread.sleep(100)
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
                        
                        Thread.sleep(500)
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

        captureThread?.let { thread ->
            try {
                thread.join(2000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        captureThread = null

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
