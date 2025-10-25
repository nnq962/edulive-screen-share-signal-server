package com.codewithkael.webrtcscreenshare.socket

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import com.codewithkael.webrtcscreenshare.utils.DataModel
import com.codewithkael.webrtcscreenshare.utils.DataModelType
import com.codewithkael.webrtcscreenshare.utils.DeviceInfo
import com.codewithkael.webrtcscreenshare.config.AppConfig
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.Exception
import kotlin.jvm.Volatile

@Singleton
class SocketClient @Inject constructor(
    private val gson: Gson,
    @ApplicationContext private val context: Context
){
    private var deviceId: String? = null
    private var wsUrl: String = "ws://192.168.1.101:3001/ws"
    
    companion object {
        private var webSocket:WebSocketClient?=null
        private const val PREFS_NAME = "webrtc_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        @Volatile private var isConnecting = false
    }

    var listener:Listener?=null
    private val mainScope = MainScope()
    
    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        if (!saved.isNullOrBlank()) return saved
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun init(
        deviceName: String = AppConfig.DEVICE_NAME,
        wsUrl: String = AppConfig.WS_URL,
        fixedDeviceId: String? = if (AppConfig.FIXED_DEVICE_ID.isBlank()) null else AppConfig.FIXED_DEVICE_ID
    ){
        // Persisted device ID (re-used across app restarts)
        deviceId = if (!fixedDeviceId.isNullOrBlank()) {
            // Save override and use it from now on
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_DEVICE_ID, fixedDeviceId).apply()
            fixedDeviceId
        } else getOrCreateDeviceId()
        this.wsUrl = wsUrl
        
        // Avoid opening a second socket if one is already open or connecting
        if (webSocket != null && (webSocket?.isOpen == true || isConnecting)) {
            Log.d("EDU_SCREEN", "WebSocket already open/connecting, skip init")
            return
        }

        isConnecting = true
        webSocket= object : WebSocketClient(URI(wsUrl)){
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("EDU_SCREEN", "WebSocket connected")
                isConnecting = false
                sendMessageToSocket(
                    DataModel(
                        type = "DEVICE_REGISTER",
                        deviceId = deviceId,
                        deviceInfo = DeviceInfo(
                            deviceId = deviceId!!,
                            name = deviceName,
                            type = "android-device"
                        )
                    )
                )
                // Start heartbeat
                CoroutineScope(Dispatchers.IO).launch {
                    while (webSocket?.isOpen == true) {
                        try {
                            sendMessageToSocket(
                                DataModel(
                                    type = "HEARTBEAT",
                                    deviceId = deviceId
                                )
                            )
                        } catch (_: Exception) {}
                        delay(HEARTBEAT_INTERVAL_MS)
                    }
                }
            }

            override fun onMessage(message: String?) {
                val model = try {
                    gson.fromJson(message.toString(),DataModel::class.java)
                }catch (e:Exception){
                    null
                }
                Log.d("EDU_SCREEN", "onMessage: $model")
                model?.let {
                    mainScope.launch {
                        if (it.type == "ERROR") {
                            Log.w("EDU_SCREEN", "⚠️ Server reported error: ${it.message ?: it.data ?: "unknown"}")
                            return@launch
                        }
                        if (it.type == "ADMIN_DISCONNECT") {
                            try {
                                listener?.onNewMessageReceived(
                                    DataModel(
                                        type = "DEVICE_STOP_STREAM",
                                        deviceId = deviceId
                                    )
                                )
                            } catch (_: Exception) {}
                            try { webSocket?.close(4000, "Admin disconnect") } catch (_: Exception) {}
                            return@launch
                        }
                        listener?.onNewMessageReceived(it)
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("EDU_SCREEN", "WebSocket closed: $code, $reason")
                isConnecting = false
                mainScope.launch {
                    try {
                        listener?.onNewMessageReceived(
                            DataModel(
                                type = "DEVICE_STOP_STREAM",
                                deviceId = deviceId
                            )
                        )
                    } catch (_: Exception) {}
                }
                // Socket referansını sıfırla, bekleyen görevler dursun
                webSocket = null
                // Otomatik reconnect devre dışı bırakıldı. Kullanıcı manuel yeniden bağlanacak.
            }

            override fun onError(ex: Exception?) {
                Log.e("EDU_SCREEN", "WebSocket error", ex)
                isConnecting = false
            }

        }
        webSocket?.connect()
    }


    fun sendMessageToSocket(message:Any?){
        try {
            if (webSocket?.isOpen == true) {
                webSocket?.send(gson.toJson(message))
            } else {
                Log.w("EDU_SCREEN", "⚠️ Tried to send message while socket is not open: $message")
            }
        }catch (e:Exception){
            e.printStackTrace()
        }
    }
    
    fun sendOffer(targetDeviceId: String, offer: Any) {
        Log.d("EDU_SCREEN", "=== SEND OFFER ===")
        try {
            sendMessageToSocket(
                DataModel(
                    type = "OFFER",
                    deviceId = deviceId,
                    targetDeviceId = targetDeviceId,
                    offer = offer
                )
            )
            Log.d("EDU_SCREEN", "✅ OFFER message sent to $targetDeviceId")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in sendOffer: ${e.message}", e)
        }
    }
    
    fun sendAnswer(viewerId: String, answer: Any) {
        sendMessageToSocket(
            DataModel(
                type = "ANSWER",
                deviceId = deviceId,
                viewerId = viewerId,
                answer = answer
            )
        )
    }
    
    fun sendIceCandidate(targetId: String, candidate: Any) {
        Log.d("EDU_SCREEN", "=== SEND ICE CANDIDATE ===")
        try {
            sendMessageToSocket(
                DataModel(
                    type = "ICE_CANDIDATE",
                    deviceId = deviceId,
                    targetDeviceId = targetId,
                    candidate = candidate
                )
            )
            Log.d("EDU_SCREEN", "✅ ICE_CANDIDATE message sent to $targetId")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in sendIceCandidate: ${e.message}", e)
        }
    }
    
    fun startStreaming() {
        Log.d("EDU_SCREEN", "=== START STREAMING ===")
        try {
            sendMessageToSocket(
                DataModel(
                    type = "DEVICE_START_STREAM",
                    deviceId = deviceId
                )
            )
            Log.d("EDU_SCREEN", "✅ DEVICE_START_STREAM message sent")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in startStreaming: ${e.message}", e)
        }
    }
    
    fun stopStreaming() {
        sendMessageToSocket(
            DataModel(
                type = "DEVICE_STOP_STREAM",
                deviceId = deviceId
            )
        )
    }
    
    private var audioChunkCount = 0L
    private var lastAudioLogTime = 0L
    
    /**
     * Legacy method kept for compatibility - binary mode is always enabled for performance
     */
    fun configureBinaryProtocol(enabled: Boolean, enableLogging: Boolean = true) {
        android.util.Log.i("BINARY_CONFIG", "📊 Binary protocol: ALWAYS ENABLED (high performance mode)")
    }
    
    /**
     * Send internal audio using binary protocol only (high performance)
     */
    fun sendInternalAudioBinary(audioData: ByteArray, sampleRate: Int, channels: Int) {
        try {
            if (webSocket?.isOpen != true) {
                android.util.Log.e("AUDIO_SEND", "❌ WebSocket not open! Audio dropped. Socket state: ${webSocket?.isOpen}")
                return
            }
            
            val binaryMessage = createBinaryAudioMessage(audioData, sampleRate, channels)
            webSocket?.send(binaryMessage)
            
            audioChunkCount++
            
            // Optimized logging - only every 5 seconds to reduce overhead
            val now = System.currentTimeMillis()
            if (now - lastAudioLogTime >= 5000) {
                android.util.Log.d("AUDIO_SEND", "📤 [BINARY] High-perf mode: #$audioChunkCount chunks, ${audioData.size}→${binaryMessage.size} bytes, rate=$sampleRate")
                lastAudioLogTime = now
            }
            
        } catch (e: Exception) {
            android.util.Log.e("AUDIO_SEND", "❌ Failed to send binary audio: ${e.message}", e)
        }
    }
    
    /**
     * Create binary audio message according to protocol specification
     */
    private fun createBinaryAudioMessage(audioData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        // Get current device ID safely
        val currentDeviceId = this.deviceId ?: getOrCreateDeviceId()
        val deviceIdBytes = currentDeviceId.toByteArray(Charsets.UTF_8)
        val deviceIdLen = deviceIdBytes.size.toByte()
        val payloadLen = audioData.size
        
        // Calculate total message size: header(12) + deviceId + payload
        val totalSize = 12 + deviceIdLen + payloadLen
        val buffer = ByteArray(totalSize)
        
        var offset = 0
        
        // Header (12 bytes)
        buffer[offset++] = 0x01 // Version
        buffer[offset++] = 0x01 // MessageType (INTERNAL_AUDIO)
        
        // SampleRate (2 bytes, little-endian)
        buffer[offset++] = (sampleRate and 0xFF).toByte()
        buffer[offset++] = ((sampleRate shr 8) and 0xFF).toByte()
        
        buffer[offset++] = channels.toByte() // Channels
        buffer[offset++] = deviceIdLen // DeviceIdLen
        
        // PayloadLen (4 bytes, little-endian)
        buffer[offset++] = (payloadLen and 0xFF).toByte()
        buffer[offset++] = ((payloadLen shr 8) and 0xFF).toByte()
        buffer[offset++] = ((payloadLen shr 16) and 0xFF).toByte()
        buffer[offset++] = ((payloadLen shr 24) and 0xFF).toByte()
        
        // Reserved (2 bytes)
        buffer[offset++] = 0x00
        buffer[offset++] = 0x00
        
        // Device ID
        System.arraycopy(deviceIdBytes, 0, buffer, offset, deviceIdLen.toInt())
        offset += deviceIdLen
        
        // Audio Payload
        System.arraycopy(audioData, 0, buffer, offset, payloadLen)
        
        return buffer
    }
    
    fun onDestroy(){
        webSocket?.close()
        webSocket = null
    }

    fun setFixedDeviceId(newId: String){
        if (newId.isNotBlank()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            deviceId = newId
        }
    }

    interface Listener {
        fun onNewMessageReceived(model:DataModel)
    }
}
