package com.codewithkael.webrtcscreenshare.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.codewithkael.webrtcscreenshare.gesture.GestureDispatcher
import com.codewithkael.webrtcscreenshare.gesture.PointerManager
import com.codewithkael.webrtcscreenshare.gesture.SimpleGestureHandler
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand

/**
 * AccessibilityService for remote control touch gestures
 * Focused only on touch/gesture handling, no keyboard support
 */
@RequiresApi(Build.VERSION_CODES.O)
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteControl"
        private var serviceInstance: RemoteControlAccessibilityService? = null


        fun dispatchCommand(command: RemoteControlCommand) {
            val service = serviceInstance
            if (service == null) {
                Log.w(TAG, "⚠️ Service not connected; cannot execute command")
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Log.w(TAG, "⚠️ Gesture dispatch requires API 24+")
                return
            }
            service.handleCommand(command)

        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Gesture handlers
    private lateinit var dispatcher: GestureDispatcher
    private lateinit var pointerManager: PointerManager
    private lateinit var simpleGestureHandler: SimpleGestureHandler


    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this

        // Initialize gesture handlers
        dispatcher = GestureDispatcher(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pointerManager = PointerManager(dispatcher)
        }
        simpleGestureHandler = SimpleGestureHandler(this)
        keyboardHelper.setup(this) {
            getRootInActiveWindow()
        }
        Log.d(TAG, "✅ AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        serviceInstance = null
        Log.d(TAG, "ℹ️ AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceInstance = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pointerManager.clear()
        }
        dispatcher.clear()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for gesture dispatch
    }

    override fun onInterrupt() {
        // Clear all gestures on interrupt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pointerManager.clear()
        }
    }

    /**
     * Handle incoming control command
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleCommand(command: RemoteControlCommand) {
        val metrics = getScreenMetrics()
        when (command.type.uppercase()) {
            "POINTER" -> mainHandler.post {
                handlePointerCommand(command, metrics)
            }

            "KEYBOARD" -> keyboardHelper.handleKeyboardCommand(command)
            "TAP" -> mainHandler.post {
                handleTapCommand(command, metrics)
            }

            "SWIPE" -> mainHandler.post {
                handleSwipeCommand(command, metrics)
            }

            else -> Log.w(TAG, "⚠️ Unknown command type: ${command.type}")
        }
    }
    /**
     * Handle streaming pointer commands (DOWN, MOVE, UP, CANCEL)
     */
    private fun handlePointerCommand(command: RemoteControlCommand, metrics: DisplayMetrics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "⚠️ Streaming pointer requires API 26+")
            return
        }

        val pointerId = command.pointerId ?: 0
        val action = command.action?.uppercase() ?: run {
            Log.w(TAG, "⚠️ Pointer command missing action")
            return
        }

        val x = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
        val y = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
        val duration = command.durationMs.coerceAtLeast(8)

        Log.v(
            TAG,
            "🖱️ POINTER $action id=$pointerId type=${command.pointerType} at (${x.toInt()}, ${y.toInt()}) duration=${duration}ms"
        )

        when (action) {
            "DOWN" -> pointerManager.startPointer(pointerId, x, y, command.pointerType, duration)
            "MOVE" -> pointerManager.movePointer(pointerId, x, y, duration, willContinue = true)
            "UP" -> pointerManager.movePointer(pointerId, x, y, duration, willContinue = false)
            "CANCEL" -> pointerManager.cancelPointer(pointerId)
            else -> Log.w(TAG, "⚠️ Unknown pointer action: $action")
        }
    }

    /**
     * Handle simple TAP command
     */
    private fun handleTapCommand(command: RemoteControlCommand, metrics: DisplayMetrics) {
        val x = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
        val y = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)

        Log.d(TAG, "👆 TAP at (${x.toInt()}, ${y.toInt()})")
        simpleGestureHandler.tap(x, y, command.durationMs)
    }

    /**
     * Handle simple SWIPE command
     */
    private fun handleSwipeCommand(command: RemoteControlCommand, metrics: DisplayMetrics) {
        val startX = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
        val startY = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
        val endX = resolveCoordinate(
            command.x2 ?: command.x,
            command.normalizedX2 ?: command.normalizedX,
            metrics.widthPixels
        )
        val endY = resolveCoordinate(
            command.y2 ?: command.y,
            command.normalizedY2 ?: command.normalizedY,
            metrics.heightPixels
        )

        Log.d(
            TAG,
            "➡️ SWIPE from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()})"
        )
        simpleGestureHandler.swipe(startX, startY, endX, endY, command.durationMs)
    }

    /**
     * Get screen metrics
     */
    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        } else {
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    /**
     * Resolve coordinate from raw or normalized value
     */
    private fun resolveCoordinate(raw: Float, normalized: Float?, max: Int): Float {
        normalized?.let {
            return (it * max).coerceIn(0f, max.toFloat())
        }
        return raw.coerceIn(0f, max.toFloat())
    }
}
