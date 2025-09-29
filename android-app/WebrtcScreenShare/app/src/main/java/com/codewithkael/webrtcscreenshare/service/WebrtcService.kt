package com.codewithkael.webrtcscreenshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    }

    @Inject lateinit var mainRepository: MainRepository

    private lateinit var notificationManager: NotificationManager
    private lateinit var username:String
    private var wsUrl: String = "ws://192.168.1.101:3001/ws"
    private var isInitialized: Boolean = false
    private var isStreaming: Boolean = false

    override fun onCreate() {
        super.onCreate()
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
                        mainRepository.startScreenCapturing(view)
                        mainRepository.sendScreenShareConnection(it)
                    }
                }
                "PrepareStreamingIntent"->{
                    startServiceWithNotification()

                    val permission = screenPermissionIntent
                    val view = surfaceView
                    if (permission == null || view == null){
                        stopMyService()
                        return START_NOT_STICKY
                    }

                    mainRepository.setPermissionIntentToWebrtcClient(permission)
                    mainRepository.startScreenCapturing(view)
                    isStreaming = false
                }
            }
        }

        return START_STICKY
    }

    private fun stopMyService(){
        if (isInitialized) {
            mainRepository.onDestroy()
        }
        stopSelf()
        notificationManager.cancelAll()
        isInitialized = false
        isStreaming = false
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
