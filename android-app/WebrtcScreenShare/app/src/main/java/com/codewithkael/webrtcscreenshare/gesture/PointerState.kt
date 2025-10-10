package com.codewithkael.webrtcscreenshare.gesture

import android.accessibilityservice.GestureDescription
import java.util.ArrayDeque

/**
 * Represents the state of a single pointer during a gesture
 */
data class PointerState(
    val pointerId: Int,
    val pointerType: String?,
    var stroke: GestureDescription.StrokeDescription,
    var lastX: Float,
    var lastY: Float,
    val segments: ArrayDeque<PointerSegment> = ArrayDeque(),
    var isDispatching: Boolean = false,
    var pendingDispose: Boolean = false
)

/**
 * Represents a segment of pointer movement
 */
data class PointerSegment(
    val toX: Float,
    val toY: Float,
    val duration: Long,
    val willContinue: Boolean
)
