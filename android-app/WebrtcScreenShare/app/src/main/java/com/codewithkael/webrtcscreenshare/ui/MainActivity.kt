package com.codewithkael.webrtcscreenshare.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.codewithkael.webrtcscreenshare.databinding.ActivityMainBinding
import com.codewithkael.webrtcscreenshare.config.AppConfig
import com.codewithkael.webrtcscreenshare.repository.MainRepository
import com.codewithkael.webrtcscreenshare.service.WebrtcService
import com.codewithkael.webrtcscreenshare.service.WebrtcServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.MediaStream
import org.webrtc.RTCStats
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainRepository.Listener {

    private var username:String?=null
    lateinit var views:ActivityMainBinding

    @Inject lateinit var webrtcServiceRepository: WebrtcServiceRepository
    private val capturePermissionRequestCode = 1
    private val orientationCheckHandler = Handler(Looper.getMainLooper())
    private val orientationCheckRunnable = object : Runnable {
        override fun run() {
            checkOrientationChange()
            orientationCheckHandler.postDelayed(this, 1000) // Check every second
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views= ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        try { supportActionBar?.hide() } catch (_: Throwable) {}
        init()

    }

    private fun init(){
        // Luôn dùng cấu hình tập trung từ AppConfig
        username = AppConfig.DEVICE_NAME
        val wsUrl = AppConfig.WS_URL
        
        WebrtcService.surfaceView = views.surfaceView
        WebrtcService.listener = this
        webrtcServiceRepository.startIntent(username!!, wsUrl)
        
        // Update status UI
        views.apply {
            statusText.text = "Connecting to WebSocket..."
            deviceIdText.text = "Device: $username"
            requestLayout.isVisible = false
            notificationLayout.isVisible = false
            disconnectBtn.isVisible = false
            surfaceView.isVisible = false // Hide surface view - we're sending screen, not receiving
            
            // Update WebSocket info
            wsInfoText.text = "WebSocket: $wsUrl"
        }

        // Toggle connect/disconnect button in status card
        views.toggleConnectionBtn.setOnClickListener {
            val currentText = views.statusText.text?.toString() ?: ""
            if (currentText.contains("Streaming", ignoreCase = true)) {
                // Disconnect from server/stream
                webrtcServiceRepository.endCallIntent()
                views.statusText.text = "🔌 Disconnected"
                views.statusText.setTextColor(android.graphics.Color.parseColor("#F44336"))
                views.toggleConnectionBtn.text = "Reconnect"
            } else {
                // Reconnect: stop service hard, then start fresh intent and stream
                webrtcServiceRepository.stopIntent()
                // small delay to ensure service stops before restarting
                Thread {
                    try { Thread.sleep(400) } catch (_: InterruptedException) {}
                    runOnUiThread {
                        webrtcServiceRepository.startIntent(username!!, AppConfig.WS_URL)
                        // if screen permission already granted earlier, StartStreamingToWebIntent will use it
                        webrtcServiceRepository.startStreamingToWeb()
                        views.statusText.text = "🔄 Reconnecting..."
                        views.statusText.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                        views.toggleConnectionBtn.text = "Disconnect"
                    }
                }.start()
            }
        }

        views.accessibilitySettingsBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("EDU_SCREEN", "❌ Failed to open accessibility settings: ${e.message}", e)
            }
        }

        // Auto start screen capture
        startScreenCapture()
        
        // Start orientation change monitoring
        startOrientationMonitoring()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != capturePermissionRequestCode) return
        WebrtcService.screenPermissionIntent = data
        // Auto start streaming to web
        webrtcServiceRepository.startStreamingToWeb()
    }

    private fun startScreenCapture(){
        val mediaProjectionManager = application.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(), capturePermissionRequestCode
        )
    }

    override fun onConnectionRequestReceived(target: String) {
        runOnUiThread{
            views.statusText.text = "🔄 Preparing stream..."
            views.statusText.setTextColor(android.graphics.Color.parseColor("#FF9800"))
        }
    }

    override fun onConnectionConnected() {
        runOnUiThread {
            // Connection established - screen is now streaming to web
            views.apply {
                statusText.text = "✅ Streaming to Web"
                statusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                surfaceView.isVisible = false // Keep hidden - we're sending screen, not receiving
                toggleConnectionBtn.text = "Disconnect"
            }
        }
    }

    override fun onCallEndReceived() {
        runOnUiThread {
            // Call ended - show disconnected state, no auto-reconnect
            views.apply {
                statusText.text = "🔌 Disconnected"
                statusText.setTextColor(android.graphics.Color.parseColor("#F44336"))
                surfaceView.isVisible = false // Keep hidden - we're sending screen, not receiving
                toggleConnectionBtn.text = "Reconnect"
            }
        }
    }

    override fun onRemoteStreamAdded(stream: MediaStream) {
        runOnUiThread {
            // We're sending screen to web, not receiving remote stream
            // So we don't need to show the surface view
            Log.d("MainActivity", "Remote stream added but we're not displaying it - we're sending screen to web")
        }
    }

    private fun restartUi(){
        views.apply {
            // Keep surface view hidden - we're sending screen to web, not receiving
            surfaceView.isVisible = false
            requestLayout.isVisible = false
            notificationLayout.isVisible = false
            disconnectBtn.isVisible = false
        }
    }

    private fun startOrientationMonitoring() {
        orientationCheckHandler.post(orientationCheckRunnable)
        Log.d("EDU_SCREEN", "🔄 Started orientation monitoring")
    }

    private fun stopOrientationMonitoring() {
        orientationCheckHandler.removeCallbacks(orientationCheckRunnable)
        Log.d("EDU_SCREEN", "🛑 Stopped orientation monitoring")
    }

    private fun checkOrientationChange() {
        try {
            Log.d("EDU_SCREEN", "🔍 MainActivity: Checking orientation change...")
            // Get the MainRepository from the service and check for orientation changes
            val mainRepository = WebrtcService.getMainRepository()
            if (mainRepository != null) {
                Log.d("EDU_SCREEN", "✅ MainRepository found, calling checkOrientationChange")
                mainRepository.checkOrientationChange()
            } else {
                Log.w("EDU_SCREEN", "⚠️ MainRepository is null, cannot check orientation change")
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error checking orientation change: ${e.message}", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("EDU_SCREEN", "🔄 Configuration changed: orientation = ${newConfig.orientation}")
        Log.d("EDU_SCREEN", "🔄 Screen size: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}")
        // Immediately check for orientation change when configuration changes
        checkOrientationChange()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOrientationMonitoring()
    }

}
