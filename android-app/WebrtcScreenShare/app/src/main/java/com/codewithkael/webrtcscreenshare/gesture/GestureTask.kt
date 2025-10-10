package com.codewithkael.webrtcscreenshare.gesture

import android.accessibilityservice.GestureDescription

/**
 * Represents a queued gesture task to be dispatched
 */
data class GestureTask(
    val pointerId: Int,
    val stroke: GestureDescription.StrokeDescription,
    val endX: Float,
    val endY: Float,
    val willContinue: Boolean,
    val onResult: (Boolean) -> Unit
)
