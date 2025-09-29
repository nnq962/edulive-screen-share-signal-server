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
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.Exception
import kotlin.jvm.Volatile
import android.os.Handler
import android.os.Looper

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
    private val mainHandler = Handler(Looper.getMainLooper())
    
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
                    mainHandler.post {
                        if (it.type == "ERROR") {
                            Log.w("EDU_SCREEN", "⚠️ Server reported error: ${it.message ?: it.data ?: "unknown"}")
                            return@post
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
                            return@post
                        }
                        listener?.onNewMessageReceived(it)
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("EDU_SCREEN", "WebSocket closed: $code, $reason")
                isConnecting = false
                mainHandler.post {
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
