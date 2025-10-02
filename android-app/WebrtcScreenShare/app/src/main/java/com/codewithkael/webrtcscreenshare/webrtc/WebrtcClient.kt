package com.codewithkael.webrtcscreenshare.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.codewithkael.webrtcscreenshare.utils.DataModel
import com.codewithkael.webrtcscreenshare.utils.DataModelType
import com.codewithkael.webrtcscreenshare.socket.SocketClient
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

            val appMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(appMetrics)
            Log.d(
                "EDU_SCREEN",
                "📱 Screen size (physical): ${realWidth}x${realHeight} | app window: ${appMetrics.widthPixels}x${appMetrics.heightPixels}"
            )

            socketClient.sendMessageToSocket(
                DataModel(
                    type = "DEVICE_SCREEN_INFO",
                    deviceId = username,
                    data = mapOf(
                        "width" to realWidth,
                        "height" to realHeight
                    )
                )
            )

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
        return peerConnectionFactory.createPeerConnection(
            iceServer, observer
        )
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




    interface Listener {
        fun onTransferEventToSocket(data: DataModel)
    }
}
