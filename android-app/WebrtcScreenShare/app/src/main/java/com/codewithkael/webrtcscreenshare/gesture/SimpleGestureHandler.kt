package com.codewithkael.webrtcscreenshare.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * Handles simple one-off gestures like TAP and SWIPE
 */
class SimpleGestureHandler(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "SimpleGestureHandler"
    }

    /**
     * Perform a simple tap gesture
     */
    fun tap(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "👆 TAP at (${x.toInt()}, ${y.toInt()}) duration=${duration}ms")
    }

    /**
     * Perform a simple swipe gesture
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        service.dispatchGesture(gesture, null, null)
        
        Log.d(
            TAG,
            "➡️ SWIPE from (${startX.toInt()}, ${startY.toInt()}) to (${endX.toInt()}, ${endY.toInt()}) duration=${duration}ms"
        )
    }
}
