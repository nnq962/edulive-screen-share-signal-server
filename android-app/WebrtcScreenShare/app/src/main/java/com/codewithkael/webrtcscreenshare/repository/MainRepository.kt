package com.codewithkael.webrtcscreenshare.repository

import android.content.Intent
import android.os.Build
import android.util.Log
import com.codewithkael.webrtcscreenshare.audio.InternalAudioCapturer
import com.codewithkael.webrtcscreenshare.socket.SocketClient
import com.codewithkael.webrtcscreenshare.utils.DataModel
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand
import com.codewithkael.webrtcscreenshare.webrtc.MyPeerObserver
import com.codewithkael.webrtcscreenshare.webrtc.WebrtcClient
import com.codewithkael.webrtcscreenshare.service.RemoteControlAccessibilityService
import com.google.gson.Gson
import org.webrtc.*
import javax.inject.Inject


class MainRepository @Inject constructor(
    private val socketClient: SocketClient,
    private val webrtcClient: WebrtcClient,
    private val gson: Gson
) : SocketClient.Listener, WebrtcClient.Listener {

    private lateinit var username: String
    private var activeViewerId: String? = null
    private lateinit var surfaceView: SurfaceViewRenderer
    private var wsUrl: String = "ws://192.168.1.101:3001/ws"
    var listener: Listener? = null
    private var isStreaming = false
    private var isScreenCapturing = false
    private var internalAudioCapturer: InternalAudioCapturer? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null

    fun init(username: String, surfaceView: SurfaceViewRenderer, wsUrl: String = "ws://192.168.1.101:3001/ws") {
        this.username = username
        this.surfaceView = surfaceView
        this.wsUrl = wsUrl
        initSocket()
        initWebrtcClient()

    }

    private fun initSocket() {
        socketClient.listener = this
        socketClient.init(username, wsUrl)
    }

    fun setPermissionIntentToWebrtcClient(intent:Intent){
        webrtcClient.setPermissionIntent(intent)
        
        // Get MediaProjection for internal audio
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mediaProjectionManager = android.content.Context.MEDIA_PROJECTION_SERVICE.let { service ->
                    // We'll get MediaProjection from Service later
                }
                Log.d("EDU_SCREEN", "🎧 MediaProjection will be set from Service")
            } catch (e: Exception) {
                Log.e("EDU_SCREEN", "❌ Failed to prepare MediaProjection: ${e.message}", e)
            }
        }
    }
    
    fun setMediaProjectionForAudio(projection: android.media.projection.MediaProjection) {
        Log.d("EDU_SCREEN", "🎧 setMediaProjectionForAudio() called")
        this.mediaProjection = projection
        Log.d("EDU_SCREEN", "🎧 MediaProjection stored")
        
        // Delay audio capture start to avoid conflict with WebRTC
        Log.d("EDU_SCREEN", "🎧 Delaying audio capture start by 2 seconds to let WebRTC stabilize...")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d("EDU_SCREEN", "🎧 Starting internal audio capture after delay")
            startInternalAudioCapture()
            Log.d("EDU_SCREEN", "🎧 startInternalAudioCapture() completed")
        }, 2000) // 2 second delay
    }
    
    private fun startInternalAudioCapture() {
        Log.d("EDU_SCREEN", "🎧 startInternalAudioCapture() method entered")
        Log.d("EDU_SCREEN", "🎧 Android SDK: ${Build.VERSION.SDK_INT}")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w("EDU_SCREEN", "⚠️ Internal audio requires Android 10+ (SDK >= 29, current: ${Build.VERSION.SDK_INT})")
            return
        }
        
        Log.d("EDU_SCREEN", "✅ Android version check passed")
        
        if (mediaProjection == null) {
            Log.w("EDU_SCREEN", "⚠️ MediaProjection not set, cannot capture internal audio")
            return
        }
        
        Log.d("EDU_SCREEN", "✅ MediaProjection is available")
        
        try {
            Log.d("EDU_SCREEN", "🎧 Stopping previous audio capture if exists...")
            stopInternalAudioCapture() // Stop if already running
            
            Log.d("EDU_SCREEN", "🎧 Creating InternalAudioCapturer instance...")
            internalAudioCapturer = InternalAudioCapturer(mediaProjection!!) { audioData, sampleRate, channels ->
                // Send audio data via WebSocket
                socketClient.sendInternalAudio(audioData, sampleRate, channels)
            }
            
            Log.d("EDU_SCREEN", "🎧 Calling startCapture()...")
            val success = internalAudioCapturer?.startCapture() ?: false
            if (success) {
                Log.d("EDU_SCREEN", "✅ Internal audio capture started successfully!")
            } else {
                Log.e("EDU_SCREEN", "❌ Failed to start internal audio capture - startCapture() returned false")
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Exception in startInternalAudioCapture: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    private fun stopInternalAudioCapture() {
        internalAudioCapturer?.stopCapture()
        internalAudioCapturer = null
    }

    fun sendScreenShareConnection(target: String){
        socketClient.sendMessageToSocket(
            DataModel(
                type = "DEVICE_START_STREAM",
                deviceId = username, // Using username as deviceId for backward compatibility
                targetDeviceId = target
            )
        )
    }
    
    fun startStreamingForViewer(viewerId: String){
        Log.d("EDU_SCREEN", "=== START STREAMING FOR VIEWER ===")
        if (!isScreenCapturing) {
            Log.w("EDU_SCREEN", "⚠️ Screen capture not initialized; attempting to start now")
            startScreenCapturing(surfaceView)
            if (!isScreenCapturing) {
                Log.e("EDU_SCREEN", "❌ Unable to start screen capture; aborting stream")
                return
            }
        }
        if (isStreaming && activeViewerId == viewerId) {
            Log.d("EDU_SCREEN", "Already streaming to viewer $viewerId, skip re-init")
            return
        }

        try {
            activeViewerId = viewerId
            isStreaming = true
            Log.d("EDU_SCREEN", "🎯 Target viewer: $viewerId")
            socketClient.startStreaming()
            Log.d("EDU_SCREEN", "✅ DEVICE_START_STREAM sent")
            webrtcClient.restart()
            Log.d("EDU_SCREEN", "🔁 Peer connection restarted for fresh offer")
            startScreenCapturing(surfaceView)
            webrtcClient.call(viewerId)
            Log.d("EDU_SCREEN", "✅ Offer dispatched for viewer $viewerId")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in startStreamingForViewer: ${e.message}", e)
            isStreaming = false
        }
    }

    fun startScreenCapturing(surfaceView: SurfaceViewRenderer){
        Log.d("EDU_SCREEN", "=== START SCREEN CAPTURING ===")
        try {
            webrtcClient.startScreenCapturing(surfaceView)
            Log.d("EDU_SCREEN", "✅ Screen capturing started")
            isScreenCapturing = true
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in startScreenCapturing: ${e.message}", e)
        }
    }

    fun startCall(target: String){
        webrtcClient.call(target)
    }

    fun sendCallEndedToOtherPeer(){
        // Nếu target chưa được gán (chưa có viewer cụ thể), gửi tín hiệu STOP chung
        if (activeViewerId != null) {
            socketClient.sendMessageToSocket(
                DataModel(
                    type = "DEVICE_STOP_STREAM",
                    deviceId = username,
                    targetDeviceId = activeViewerId
                )
            )
        } else {
            // Fallback: chỉ thông báo dừng stream cho server
            socketClient.stopStreaming()
        }
        stopStreamingForViewer(activeViewerId, notifyServer = false)
    }

    fun stopStreamingForViewer(viewerId: String? = null, notifyServer: Boolean = true) {
        if (!isStreaming) {
            return
        }
        if (viewerId != null && activeViewerId != null && viewerId != activeViewerId) {
            Log.d("EDU_SCREEN", "STOP_STREAM ignored for viewer $viewerId; active viewer is $activeViewerId")
            return
        }

        if (notifyServer) {
            try {
                socketClient.stopStreaming()
            } catch (e: Exception) {
                Log.e("EDU_SCREEN", "❌ Error notifying server about stop: ${e.message}", e)
            }
        }

        isStreaming = false
        activeViewerId = null
        listener?.onCallEndReceived()
    }

    fun restartRepository(){
        activeViewerId = null
        isStreaming = false
        webrtcClient.restart()
    }

    fun onDestroy(){
        stopInternalAudioCapture()
        socketClient.onDestroy()
        webrtcClient.closeConnection()
        activeViewerId = null
        isStreaming = false
        isScreenCapturing = false
        mediaProjection = null
    }

    fun checkOrientationChange() {
        Log.d("EDU_SCREEN", "🔍 MainRepository: Calling webrtcClient.checkOrientationChange()")
        webrtcClient.checkOrientationChange()
    }

    private fun initWebrtcClient() {
        webrtcClient.listener = this
        webrtcClient.initializeWebrtcClient(username, surfaceView,
            object : MyPeerObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    p0?.let { 
                        Log.d("EDU_SCREEN", "🔄 ICE candidate received: ${it.sdp}")
                        // Route ICE candidate to active viewer (fallback to legacy broadcast)
                        val targetForIce = activeViewerId ?: "web-viewer"
                        webrtcClient.sendIceCandidate(it, targetForIce)
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    super.onConnectionChange(newState)
                    Log.d("EDU_SCREEN", "onConnectionChange: $newState")
                    if (newState == PeerConnection.PeerConnectionState.CONNECTED){
                        listener?.onConnectionConnected()
                    }
                }

                override fun onAddStream(p0: MediaStream?) {
                    super.onAddStream(p0)
                    Log.d("EDU_SCREEN", "onAddStream: $p0")
                    p0?.let { listener?.onRemoteStreamAdded(it) }
                }
            })
    }

    override fun onNewMessageReceived(model: DataModel) {
        when (model.type) {
            "OFFER" -> {
                // Handle offer from web viewer
                model.offer?.let { offer ->
                    webrtcClient.onRemoteSessionReceived(
                        SessionDescription(
                            SessionDescription.Type.OFFER, offer.toString()
                        )
                    )
                    model.viewerId?.let { viewerId ->
                        activeViewerId = viewerId
                        webrtcClient.answer(viewerId)
                    }
                }
            }
            "ANSWER" -> {
                // Handle answer from web viewer
                model.answer?.let { answer ->
                    webrtcClient.onRemoteSessionReceived(
                        SessionDescription(SessionDescription.Type.ANSWER, answer.toString())
                    )
                }
            }
            "ICE_CANDIDATE" -> {
                // Handle ICE candidate from web viewer
                model.candidate?.let { candidate ->
                    val iceCandidate = try {
                        gson.fromJson(candidate.toString(), IceCandidate::class.java)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    iceCandidate?.let {
                        webrtcClient.addIceCandidate(it)
                    }
                }
            }
            "ROOM_INFO" -> {
                // Handle room info - devices connected
                Log.d("EDU_SCREEN", "Room info received: ${model.data}")
            }
            "REQUEST_STREAM" -> {
                model.viewerId?.let { viewerId ->
                    listener?.onConnectionRequestReceived(viewerId)
                    startStreamingForViewer(viewerId)
                }
            }
            "DEVICE_START_STREAM" -> {
                // Legacy support - treat as request with provided target
                val viewerId = model.viewerId ?: model.deviceId ?: model.targetDeviceId ?: ""
                if (viewerId.isNotBlank()) {
                    listener?.onConnectionRequestReceived(viewerId)
                    startStreamingForViewer(viewerId)
                }
            }
            "DEVICE_STOP_STREAM" -> {
                //notify ui call is ended
                stopStreamingForViewer(model.viewerId)
            }
            "STOP_STREAM" -> {
                stopStreamingForViewer(model.viewerId)
            }
            "CONTROL_COMMAND" -> {
                model.data?.let { payload ->
                    try {
                        val json = gson.toJson(payload)
                        val command = gson.fromJson(json, RemoteControlCommand::class.java)
                        RemoteControlAccessibilityService.dispatchCommand(command)
                    } catch (e: Exception) {
                        Log.e("EDU_SCREEN", "❌ Error parsing control command: ${e.message}", e)
                    }
                }
            }
            else -> Unit
        }
    }

    override fun onTransferEventToSocket(data: DataModel) {
        socketClient.sendMessageToSocket(data)
    }

    interface Listener {
        fun onConnectionRequestReceived(target: String)
        fun onConnectionConnected()
        fun onCallEndReceived()
        fun onRemoteStreamAdded(stream: MediaStream)
    }
}
