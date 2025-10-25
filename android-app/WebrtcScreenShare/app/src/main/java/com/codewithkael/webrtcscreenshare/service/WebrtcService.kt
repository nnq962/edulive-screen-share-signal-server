package com.codewithkael.webrtcscreenshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.codewithkael.webrtcscreenshare.R
import com.codewithkael.webrtcscreenshare.repository.MainRepository
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@AndroidEntryPoint
class WebrtcService @Inject constructor() : Service() , MainRepository.Listener {


    companion object {
        var screenPermissionIntent : Intent ?= null
        var surfaceView:SurfaceViewRenderer?=null
        var listener: MainRepository.Listener?=null
        private var instance: WebrtcService? = null
        
        fun getMainRepository(): MainRepository? {
            return instance?.mainRepository
        }
    }

    @Inject lateinit var mainRepository: MainRepository
    @Inject lateinit var socketClient: com.codewithkael.webrtcscreenshare.socket.SocketClient

    private lateinit var notificationManager: NotificationManager
    private lateinit var username:String
    private var wsUrl: String = "ws://192.168.1.101:3001/ws"
    private var isInitialized: Boolean = false
    private var isStreaming: Boolean = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(
            NotificationManager::class.java
        )
        mainRepository.listener = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent!=null){
            when(intent.action){
                "StartIntent"->{
                    this.username = intent.getStringExtra("username").toString()
                    this.wsUrl = intent.getStringExtra("wsUrl") ?: "ws://192.168.1.101:3001/ws"
                    mainRepository.init(username, surfaceView!!, wsUrl)
                    startServiceWithNotification()
                    isInitialized = true
                }
                "StopIntent"->{
                    stopMyService()
                }
                "EndCallIntent"->{
                    if (isInitialized) {
                        mainRepository.sendCallEndedToOtherPeer()
                        mainRepository.onDestroy()
                    }
                    stopMyService()
                }
                "AcceptCallIntent"->{
                    val target = intent.getStringExtra("target")
                    target?.let {
                        mainRepository.startCall(it)
                    }
                }
                "RequestConnectionIntent"->{
                    val target= intent.getStringExtra("target")
                    target?.let {
                        // Bảo đảm service đã là foreground trước khi bắt đầu MediaProjection
                        startServiceWithNotification()

                        val permission = screenPermissionIntent
                        val view = surfaceView
                        if (permission == null || view == null){
                            stopMyService()
                            return START_NOT_STICKY
                        }
                        mainRepository.setPermissionIntentToWebrtcClient(permission)
                        
                        // Start screen capturing FIRST
                        mainRepository.startScreenCapturing(view)
                        mainRepository.sendScreenShareConnection(it)
                        
                        // Setup audio AFTER screen capture with delay
                        serviceScope.launch {
                            delay(1500)
                            Log.d("EDU_SCREEN", "🎧 Setting up audio for RequestConnectionIntent")
                            setupMediaProjectionForAudio(permission)
                        }
                    }
                }
                "PrepareStreamingIntent"->{
                    Log.d("EDU_SCREEN", "🚀 PrepareStreamingIntent started")
                    startServiceWithNotification()

                    val permission = screenPermissionIntent
                    val view = surfaceView
                    if (permission == null || view == null){
                        Log.e("EDU_SCREEN", "❌ Permission or view is null - permission: ${permission != null}, view: ${view != null}")
                        stopMyService()
                        return START_NOT_STICKY
                    }

                    Log.d("EDU_SCREEN", "✅ Permission and view are ready")
                    mainRepository.setPermissionIntentToWebrtcClient(permission)
                    
                    Log.d("EDU_SCREEN", "📹 Starting screen capturing FIRST")
                    mainRepository.startScreenCapturing(view)
                    
                    // IMPORTANT: Wait for screen capture to fully initialize its MediaProjection
                    // before creating a second MediaProjection for audio
                    // Use coroutines to avoid blocking service thread
                    Log.d("EDU_SCREEN", "⏰ Scheduling audio setup in 1.5 seconds...")
                    serviceScope.launch {
                        delay(1500) // 1.5 seconds delay
                        Log.d("EDU_SCREEN", "🎧 Now setting up MediaProjection for audio")
                        setupMediaProjectionForAudio(permission)
                    }
                    
                    isStreaming = false
                    Log.d("EDU_SCREEN", "✅ PrepareStreamingIntent completed (audio will start in 1.5s)")
                }
            }
        }

        return START_STICKY
    }

    private fun setupMediaProjectionForAudio(permissionIntent: Intent) {
        Log.d("EDU_SCREEN", "🎧 setupMediaProjectionForAudio() called")
        Log.d("EDU_SCREEN", "🎧 Android SDK version: ${Build.VERSION.SDK_INT} (Need >= 29)")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d("EDU_SCREEN", "✅ Android version check passed (SDK >= 29)")
            try {
                Log.d("EDU_SCREEN", "🎧 Getting MediaProjectionManager...")
                val mediaProjectionManager = getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager
                
                Log.d("EDU_SCREEN", "🎧 Calling getMediaProjection()...")
                val mediaProjection = mediaProjectionManager.getMediaProjection(
                    android.app.Activity.RESULT_OK,
                    permissionIntent
                )
                
                if (mediaProjection != null) {
                    Log.d("EDU_SCREEN", "✅ MediaProjection obtained successfully!")
                    Log.d("EDU_SCREEN", "🎧 MediaProjection instance: ${System.identityHashCode(mediaProjection)}")
                    
                    // Add callback to monitor MediaProjection state
                    mediaProjection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                        override fun onStop() {
                            super.onStop()
                            Log.w("EDU_SCREEN", "⚠️ MediaProjection was stopped externally!")
                        }
                    }, null)
                    
                    mainRepository.setMediaProjectionForAudio(mediaProjection)
                    Log.d("EDU_SCREEN", "✅ MediaProjection set for internal audio capture")
                } else {
                    Log.w("EDU_SCREEN", "⚠️ MediaProjection is null - internal audio not available")
                }
            } catch (e: Exception) {
                Log.e("EDU_SCREEN", "❌ Failed to get MediaProjection: ${e.message}", e)
                e.printStackTrace()
            }
        } else {
            Log.w("EDU_SCREEN", "⚠️ Android 10+ required for internal audio (Current: SDK ${Build.VERSION.SDK_INT})")
        }
    }

    private fun stopMyService(){
        if (isInitialized) {
            mainRepository.onDestroy()
        }
        stopSelf()
        notificationManager.cancelAll()
        isInitialized = false
        isStreaming = false
        instance = null
    }

    private fun startServiceWithNotification(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val notificationChannel = NotificationChannel(
                "channel1","foreground",NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(this,"channel1")
                .setSmallIcon(R.mipmap.ic_launcher)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                startForeground(1, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification.build())
            }
        }

    }

    override fun onConnectionRequestReceived(target: String) {
        listener?.onConnectionRequestReceived(target)
    }

    override fun onConnectionConnected() {
        listener?.onConnectionConnected()
    }

    override fun onCallEndReceived() {
        listener?.onCallEndReceived()
        isStreaming = false
    }

    override fun onRemoteStreamAdded(stream: MediaStream) {
        listener?.onRemoteStreamAdded(stream)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
