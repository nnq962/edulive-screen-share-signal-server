package com.codewithkael.webrtcscreenshare.webrtc

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.codewithkael.webrtcscreenshare.utils.DataModel
import com.codewithkael.webrtcscreenshare.utils.DataModelType
import com.codewithkael.webrtcscreenshare.socket.SocketClient
import com.codewithkael.webrtcscreenshare.config.AppConfig
import com.google.gson.Gson
import org.webrtc.*
import org.webrtc.PeerConnection.Observer
import javax.inject.Inject

class WebrtcClient @Inject constructor(
    private val context: Context, 
    private val gson: Gson,
    private val socketClient: SocketClient
) {

    private lateinit var username: String
    private lateinit var observer: Observer
    private lateinit var localSurfaceView: SurfaceViewRenderer
    var listener: Listener? = null
    private var permissionIntent:Intent?=null

    private var peerConnection: PeerConnection? = null
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }
    private val iceServer = listOf(
        PeerConnection.IceServer(
            "turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"
        )
    )

    private var screenCapturer : VideoCapturer?=null
    private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val localTrackId="local_track"
    private val localStreamId="local_stream"
    private var localVideoTrack:VideoTrack?=null
    private var localStream: MediaStream?=null
    private var isSurfaceInitialized: Boolean = false
    private var currentScreenWidth: Int = 0
    private var currentScreenHeight: Int = 0
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    
    init {
        initPeerConnectionFactory(context)
    }

    fun initializeWebrtcClient(
        username: String, view: SurfaceViewRenderer, observer: Observer
    ) {
        Log.d("EDU_SCREEN", "=== INITIALIZE WEBRTC CLIENT ===")
        try {
            this.username = username
            this.observer = observer
            Log.d("EDU_SCREEN", "🔄 Creating peer connection")
            peerConnection = createPeerConnection(observer)
            Log.d("EDU_SCREEN", "✅ Peer connection created")
            Log.d("EDU_SCREEN", "🔄 Initializing surface view")
            initSurfaceView(view)
            Log.d("EDU_SCREEN", "✅ Surface view initialized")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in initializeWebrtcClient: ${e.message}", e)
        }
    }

    fun setPermissionIntent(intent: Intent) {
        this.permissionIntent = intent
    }

    private fun initSurfaceView(view: SurfaceViewRenderer){
        this.localSurfaceView = view
        if (isSurfaceInitialized) return
        view.run {
            setMirror(false)
            setEnableHardwareScaler(true)
            try {
                init(eglBaseContext,null)
                isSurfaceInitialized = true
            } catch (e: IllegalStateException) {
                Log.w("EDU_SCREEN", "SurfaceView already initialized, skipping re-init")
                isSurfaceInitialized = true
            }
        }
    }

    fun startScreenCapturing(view:SurfaceViewRenderer){
        Log.d("EDU_SCREEN", "=== START SCREEN CAPTURING ===")
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val realMetrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
            } else {
                windowManager.defaultDisplay.getMetrics(realMetrics)
            }
            val realWidth = realMetrics.widthPixels
            val realHeight = realMetrics.heightPixels

            // Update current screen size and orientation
            currentScreenWidth = realWidth
            currentScreenHeight = realHeight
            lastOrientation = getCurrentOrientation()

            val appMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(appMetrics)
            Log.d(
                "EDU_SCREEN",
                "📱 Screen size (physical): ${realWidth}x${realHeight} | orientation: $lastOrientation | app window: ${appMetrics.widthPixels}x${appMetrics.heightPixels}"
            )

            // Send initial screen info
            sendScreenInfoToWeb(realWidth, realHeight)

            Log.d("EDU_SCREEN", "🔄 Creating surface texture helper")
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                Thread.currentThread().name,eglBaseContext
            )

            Log.d("EDU_SCREEN", "🔄 Creating screen capturer")
            screenCapturer = createScreenCapturer()
            Log.d("EDU_SCREEN", "🔄 Initializing screen capturer")
            screenCapturer!!.initialize(surfaceTextureHelper,context,localVideoSource.capturerObserver)
            Log.d("EDU_SCREEN", "🔄 Starting screen capture")
            screenCapturer!!.startCapture(realWidth,realHeight,15)

            Log.d("EDU_SCREEN", "🔄 Creating video track")
            localVideoTrack = peerConnectionFactory.createVideoTrack(localTrackId+"_video",localVideoSource)
            localVideoTrack?.addSink(view)
            Log.d("EDU_SCREEN", "🔄 Creating local media stream")
            localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
            localStream?.addTrack(localVideoTrack)
            Log.d("EDU_SCREEN", "🔄 Adding stream to peer connection")
            peerConnection?.addStream(localStream)
            Log.d("EDU_SCREEN", "✅ Screen capturing started successfully")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in startScreenCapturing: ${e.message}", e)
        }
    }

    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(permissionIntent, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d("TAG", "onStop: stopped screen casting permission")
            }
        })
    }

    private fun initPeerConnectionFactory(application: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder().setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBaseContext)
            ).setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBaseContext, true, true
                )
            ).setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            }).createPeerConnectionFactory()
    }

    private fun createPeerConnection(observer: Observer): PeerConnection? {
        val pc = peerConnectionFactory.createPeerConnection(
            iceServer, observer
        )
        // Create a DataChannel for control commands so SCTP is negotiated in the SDP offer
        try {
            val init = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = 3
            }
            val channel = pc?.createDataChannel("control", init)
            channel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {
                    android.util.Log.d("EDU_SCREEN", "DataChannel state: ${channel.state().name}")
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null) return
                    try {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        val text = String(bytes)
                        android.util.Log.d("EDU_DC", "RX via DataChannel: $text")
                        // Expect { type: 'CONTROL_COMMAND', data: {...} }
                        val json = com.google.gson.JsonParser.parseString(text).asJsonObject
                        val type = if (json.has("type")) json.get("type").asString else null
                        if (type == "CONTROL_COMMAND" && json.has("data")) {
                            val cmd = gson.fromJson(json.get("data"), com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand::class.java)
                            com.codewithkael.webrtcscreenshare.service.RemoteControlAccessibilityService.dispatchCommand(cmd)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EDU_SCREEN", "❌ Error handling DataChannel message: ${e.message}", e)
                    }
                }
            })
        } catch (_: Exception) {}
        return pc
    }

    fun call(target: String) {
        Log.d("EDU_SCREEN", "=== CALL METHOD ===")
        try {
            Log.d("EDU_SCREEN", "🔄 Creating offer for target: $target")
            peerConnection?.createOffer(object : MySdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    super.onCreateSuccess(desc)
                    Log.d("EDU_SCREEN", "✅ Offer created successfully")
                    Log.d("EDU_SCREEN", "🔄 Setting local description")
                    peerConnection?.setLocalDescription(object : MySdpObserver() {
                        override fun onSetSuccess() {
                            super.onSetSuccess()
                            Log.d("EDU_SCREEN", "✅ Local description set successfully")
                            Log.d("EDU_SCREEN", "🔄 Sending offer via SocketClient")
                            // Send offer via SocketClient
                            socketClient.sendOffer(target, desc?.description ?: "")
                            Log.d("WebrtcClient", "✅ Offer sent successfully")
                        }
                        override fun onSetFailure(error: String?) {
                            super.onSetFailure(error)
                            Log.e("EDU_SCREEN", "❌ Failed to set local description: $error")
                        }
                    }, desc)
                }
                override fun onCreateFailure(error: String?) {
                    super.onCreateFailure(error)
                    Log.e("EDU_SCREEN", "❌ Failed to create offer: $error")
                }
            }, mediaConstraint)
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in call method: ${e.message}", e)
        }
    }

    fun answer(target: String) {
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        // Send answer via SocketClient
                        socketClient.sendAnswer(target, desc?.description ?: "")
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun onRemoteSessionReceived(sessionDescription: SessionDescription){
        peerConnection?.setRemoteDescription(MySdpObserver(),sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate){
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun sendIceCandidate(candidate: IceCandidate,target: String){
        Log.d("EDU_SCREEN", "=== SEND ICE CANDIDATE ===")
        try {
            Log.d("EDU_SCREEN", "🔄 Adding ICE candidate to peer connection")
            addIceCandidate(candidate)
            Log.d("EDU_SCREEN", "🔄 Sending ICE candidate via SocketClient to $target")
            // Send ICE candidate via SocketClient
            socketClient.sendIceCandidate(target, gson.toJson(candidate))
            Log.d("EDU_SCREEN", "✅ ICE candidate sent successfully")
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error in sendIceCandidate: ${e.message}", e)
        }
    }

    fun closeConnection(){
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            localStream?.dispose()
            peerConnection?.close()
        }catch (e:Exception){
            e.printStackTrace()
        }
        try {
            localSurfaceView.clearImage()
            localSurfaceView.release()
        } catch (_: Exception) {}
        peerConnection = null
        localStream = null
        localVideoTrack = null
        screenCapturer = null
        isSurfaceInitialized = false
    }

    fun restart(){
        closeConnection()
        localSurfaceView.let {
            it.clearImage()
            it.release()
            initializeWebrtcClient(username,it,observer)
        }
    }

    fun checkOrientationChange() {
        val currentOrientation = getCurrentOrientation()
        Log.d("EDU_SCREEN", "🔍 Checking orientation: current=$currentOrientation, last=$lastOrientation")
        if (currentOrientation != lastOrientation) {
            Log.d("EDU_SCREEN", "🔄 Orientation changed from $lastOrientation to $currentOrientation")
            handleOrientationChange()
            lastOrientation = currentOrientation
        } else {
            Log.d("EDU_SCREEN", "📱 No orientation change detected")
        }
    }

    private fun handleOrientationChange() {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realMetrics = DisplayMetrics()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
            } else {
                windowManager.defaultDisplay.getMetrics(realMetrics)
            }
            
            val newWidth = realMetrics.widthPixels
            val newHeight = realMetrics.heightPixels
            
            if (newWidth != currentScreenWidth || newHeight != currentScreenHeight) {
                Log.d("EDU_SCREEN", "📱 Screen size changed from ${currentScreenWidth}x${currentScreenHeight} to ${newWidth}x${newHeight}")
                
                currentScreenWidth = newWidth
                currentScreenHeight = newHeight
                
                // Send new screen info to web client
                sendScreenInfoToWeb(newWidth, newHeight)
                
                // Note: We don't restart screen capture to avoid MediaProjection permission issues
                // The existing capture will continue with the same resolution but web UI will adjust
                Log.d("EDU_SCREEN", "📤 Screen info sent to web client, capture continues with existing resolution")
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error handling orientation change: ${e.message}", e)
        }
    }

    private fun getCurrentOrientation(): Int {
        return context.resources.configuration.orientation
    }

    private fun sendScreenInfoToWeb(width: Int, height: Int) {
        if (username.isBlank()) {
            Log.w("EDU_SCREEN", "⚠️ Username is blank, cannot send screen info")
            return
        }
        
        // Use the same deviceId that was used for registration
        val deviceId = AppConfig.FIXED_DEVICE_ID.ifBlank { username }
        
        val dataModel = DataModel(
            type = "DEVICE_SCREEN_INFO",
            deviceId = deviceId,
            data = mapOf(
                "width" to width,
                "height" to height
            )
        )
        
        Log.d("EDU_SCREEN", "📤 Sending screen info: type=${dataModel.type}, deviceId=${dataModel.deviceId}, data=${dataModel.data}")
        socketClient.sendMessageToSocket(dataModel)
        Log.d("EDU_SCREEN", "✅ Screen info sent to web: ${width}x${height} for device: $deviceId")
    }




    interface Listener {
        fun onTransferEventToSocket(data: DataModel)
    }
}
