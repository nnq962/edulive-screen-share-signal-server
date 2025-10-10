package com.codewithkael.webrtcscreenshare.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Manages multiple pointer states for multi-touch gestures
 */
@RequiresApi(Build.VERSION_CODES.O)
class PointerManager(
    private val dispatcher: GestureDispatcher
) {
    companion object {
        private const val TAG = "PointerManager"
    }

    private val pointers = mutableMapOf<Int, PointerState>()

    /**
     * Start a new pointer gesture
     */
    fun startPointer(
        pointerId: Int,
        x: Float,
        y: Float,
        pointerType: String?,
        duration: Long
    ) {
        if (pointers.containsKey(pointerId)) {
            Log.w(TAG, "⚠️ Pointer $pointerId already active, ignoring duplicate DOWN")
            return
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration, true)

        val state = PointerState(
            pointerId = pointerId,
            pointerType = pointerType,
            stroke = stroke,
            lastX = x,
            lastY = y
        )
        pointers[pointerId] = state

        Log.d(TAG, "🟢 Pointer DOWN id=$pointerId type=$pointerType at (${x.toInt()}, ${y.toInt()})")

        dispatchStroke(pointerId, state, stroke, x, y, willContinue = true)
    }

    /**
     * Move pointer to new position
     */
    fun movePointer(
        pointerId: Int,
        x: Float,
        y: Float,
        duration: Long,
        willContinue: Boolean = true
    ) {
        val state = pointers[pointerId]
        if (state == null) {
            Log.w(TAG, "⚠️ Pointer $pointerId not found for MOVE")
            return
        }

        if (state.pendingDispose && willContinue) {
            Log.w(TAG, "⚠️ Ignoring MOVE for finishing pointer $pointerId")
            return
        }

        state.segments.add(PointerSegment(x, y, duration, willContinue))

        if (!willContinue) {
            state.pendingDispose = true
            Log.d(TAG, "⬇️ Pointer UP queued id=$pointerId at (${x.toInt()}, ${y.toInt()})")
        }

        flushQueue(pointerId, state)
    }

    /**
     * Cancel a pointer gesture
     */
    fun cancelPointer(pointerId: Int) {
        val state = pointers[pointerId]
        if (state == null) {
            Log.d(TAG, "⛔ Pointer CANCEL id=$pointerId (already released)")
            return
        }

        Log.d(TAG, "⛔ Pointer CANCEL id=$pointerId at (${state.lastX.toInt()}, ${state.lastY.toInt()})")

        dispatcher.purgeTasks(pointerId)
        state.segments.clear()
        state.pendingDispose = true
        state.segments.add(PointerSegment(state.lastX, state.lastY, 16, false))

        if (!state.isDispatching) {
            flushQueue(pointerId, state)
        }
    }

    /**
     * Flush the segment queue for a pointer
     */
    private fun flushQueue(pointerId: Int, state: PointerState) {
        if (state.isDispatching) return
        if (state.segments.isEmpty()) {
            if (state.pendingDispose) {
                pointers.remove(pointerId)
            }
            return
        }

        val segment = state.segments.removeFirst()
        val path = Path().apply {
            moveTo(state.lastX, state.lastY)
            lineTo(segment.toX, segment.toY)
        }

        val continuedStroke = state.stroke.continueStroke(
            path,
            0,
            segment.duration.coerceAtLeast(8),
            segment.willContinue
        )

        if (segment.willContinue) {
            Log.v(TAG, "↔️ Pointer MOVE id=$pointerId to (${segment.toX.toInt()}, ${segment.toY.toInt()})")
        } else {
            Log.d(TAG, "✅ Pointer END id=$pointerId at (${segment.toX.toInt()}, ${segment.toY.toInt()})")
        }

        dispatchStroke(pointerId, state, continuedStroke, segment.toX, segment.toY, segment.willContinue)

        if (!segment.willContinue && state.segments.isEmpty()) {
            state.pendingDispose = true
        }
    }

    /**
     * Dispatch a stroke through the gesture dispatcher
     */
    private fun dispatchStroke(
        pointerId: Int,
        state: PointerState,
        stroke: GestureDescription.StrokeDescription,
        endX: Float,
        endY: Float,
        willContinue: Boolean
    ) {
        state.isDispatching = true

        val task = GestureTask(pointerId, stroke, endX, endY, willContinue) { completed ->
            val current = pointers[pointerId]
            if (!completed || current == null) {
                pointers.remove(pointerId)
                dispatcher.purgeTasks(pointerId)
                return@GestureTask
            }

            current.stroke = stroke
            current.lastX = endX
            current.lastY = endY
            current.isDispatching = false

            if (!willContinue && current.segments.isEmpty()) {
                pointers.remove(pointerId)
            } else {
                flushQueue(pointerId, current)
            }
        }

        dispatcher.enqueue(task)
    }

    /**
     * Get the number of active pointers
     */
    fun getActivePointerCount(): Int = pointers.size

    /**
     * Clear all pointers
     */
    fun clear() {
        pointers.keys.toList().forEach { cancelPointer(it) }
        pointers.clear()
    }
}
