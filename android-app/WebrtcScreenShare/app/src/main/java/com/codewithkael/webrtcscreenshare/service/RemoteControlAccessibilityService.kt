package com.codewithkael.webrtcscreenshare.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private var serviceInstance: RemoteControlAccessibilityService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun dispatchCommand(command: RemoteControlCommand) {
            val service = serviceInstance
            if (service == null) {
                Log.w("EDU_SCREEN", "⚠️ AccessibilityService not connected; cannot execute command")
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.w("EDU_SCREEN", "⚠️ Gesture dispatch requires API 24+")
                return
            }
            mainHandler.post {
                service.handleCommand(command)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        Log.d("EDU_SCREEN", "✅ AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        serviceInstance = null
        Log.d("EDU_SCREEN", "ℹ️ AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceInstance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we only use this service for gesture dispatch
    }

    override fun onInterrupt() {
        // No-op
    }

    private fun handleCommand(command: RemoteControlCommand) {
        when (command.type.uppercase()) {
            "TAP" -> performTap(command.x, command.y, command.durationMs)
            "SWIPE" -> performSwipe(command, command.durationMs)
            else -> Log.w("EDU_SCREEN", "⚠️ Unknown control command type: ${command.type}")
        }
    }

    private fun performTap(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performSwipe(command: RemoteControlCommand, duration: Long) {
        val endX = command.x2 ?: command.x
        val endY = command.y2 ?: command.y
        val path = Path().apply {
            moveTo(command.x, command.y)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
