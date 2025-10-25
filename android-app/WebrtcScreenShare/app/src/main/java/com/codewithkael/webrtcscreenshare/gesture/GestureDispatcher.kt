package com.codewithkael.webrtcscreenshare.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Handles gesture dispatch queue and execution
 */
class GestureDispatcher(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "GestureDispatcher"
    }

    private val gestureQueue = ArrayDeque<GestureTask>()
    private var gestureInFlight = false
    private val mainScope = MainScope()

    /**
     * Add a gesture task to the queue
     */
    fun enqueue(task: GestureTask) {
        gestureQueue.addLast(task)
        processNext()
    }

    /**
     * Remove all pending tasks for a specific pointer
     */
    fun purgeTasks(pointerId: Int) {
        if (gestureQueue.isEmpty()) return
        val remaining = gestureQueue.filter { it.pointerId != pointerId }
        if (remaining.size == gestureQueue.size) return
        gestureQueue.clear()
        gestureQueue.addAll(remaining)
        Log.d(TAG, "Purged tasks for pointer $pointerId")
    }

    /**
     * Process the next gesture in queue
     */
    private fun processNext() {
        if (gestureInFlight) return
        if (gestureQueue.isEmpty()) return

        val task = gestureQueue.removeFirst()
        gestureInFlight = true

        val gesture = GestureDescription.Builder()
            .addStroke(task.stroke)
            .build()

        val success = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainScope.launch {
                        gestureInFlight = false
                        task.onResult(true)
                        processNext()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    mainScope.launch {
                        gestureInFlight = false
                        Log.w(TAG, "⚠️ Gesture cancelled for pointer ${task.pointerId}")
                        task.onResult(false)
                        processNext()
                    }
                }
            },
            null
        )

        if (!success) {
            mainScope.launch {
                gestureInFlight = false
                Log.e(TAG, "❌ dispatchGesture failed for pointer ${task.pointerId}")
                task.onResult(false)
                processNext()
            }
        }
    }

    /**
     * Clear all pending gestures
     */
    fun clear() {
        gestureQueue.clear()
        gestureInFlight = false
    }
}
