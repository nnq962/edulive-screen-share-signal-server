package com.codewithkael.webrtcscreenshare.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand
import java.util.ArrayDeque

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

    private data class PointerSegment(
        val toX: Float,
        val toY: Float,
        val duration: Long,
        val willContinue: Boolean
    )

    private data class PointerGestureState(
        var stroke: GestureDescription.StrokeDescription,
        var lastX: Float,
        var lastY: Float,
        val pointerType: String?,
        val queue: ArrayDeque<PointerSegment> = ArrayDeque(),
        var isDispatching: Boolean = false,
        var pendingDispose: Boolean = false
    )

    private data class GestureTask(
        val pointerId: Int,
        val stroke: GestureDescription.StrokeDescription,
        val endX: Float,
        val endY: Float,
        val willContinue: Boolean,
        val onResult: (Boolean) -> Unit
    )

    private val pointerGestures = mutableMapOf<Int, PointerGestureState>()
    private val gestureQueue = ArrayDeque<GestureTask>()
    private var gestureInFlight = false

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
        pointerGestures.clear()
        gestureQueue.clear()
        gestureInFlight = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we only use this service for gesture dispatch
    }

    override fun onInterrupt() {
        // No-op
    }

    private fun handleCommand(command: RemoteControlCommand) {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        } else {
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        when (command.type.uppercase()) {
            "POINTER" -> handlePointerCommand(command, metrics)
            "KEYBOARD" -> handleKeyboardCommand(command)
            "TAP" -> {
                val resolvedX = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
                val resolvedY = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
                logCommandDetails(command, resolvedX, resolvedY, metrics)
                performTap(resolvedX, resolvedY, command.durationMs)
            }
            "SWIPE" -> {
                val resolvedX = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
                val resolvedY = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
                logCommandDetails(command, resolvedX, resolvedY, metrics)
                performSwipe(command, command.durationMs, metrics)
            }
            else -> Log.w("EDU_SCREEN", "⚠️ Unknown control command type: ${command.type}")
        }
    }

    private fun logCommandDetails(
        command: RemoteControlCommand,
        resolvedX: Float,
        resolvedY: Float,
        metrics: DisplayMetrics
    ) {
        Log.d(
            "EDU_SCREEN",
            "🎯 Accessibility command: ${command.type} -> startRaw=(${command.x},${command.y}), endRaw=(${command.x2},${command.y2}), startNorm=(${command.normalizedX},${command.normalizedY}), endNorm=(${command.normalizedX2},${command.normalizedY2}), resolvedStart=(${resolvedX.toInt()},${resolvedY.toInt()}), duration=${command.durationMs}, screen=${metrics.widthPixels}x${metrics.heightPixels}"
        )
    }

    private fun performTap(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performSwipe(command: RemoteControlCommand, duration: Long, metrics: DisplayMetrics) {
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
            "EDU_SCREEN",
            "➡️ Swipe gesture start=(${startX.toInt()},${startY.toInt()}) end=(${endX.toInt()},${endY.toInt()}) duration=$duration"
        )
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun resolveCoordinate(raw: Float, normalized: Float?, max: Int): Float {
        normalized?.let { return (it * max).coerceIn(0f, max.toFloat()) }
        return raw.coerceIn(0f, max.toFloat())
    }

    private fun handlePointerCommand(command: RemoteControlCommand, metrics: DisplayMetrics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w("EDU_SCREEN", "⚠️ Streaming pointer commands require API 26+")
            return
        }

        val pointerId = command.pointerId ?: 0
        val action = command.action?.uppercase() ?: run {
            Log.w("EDU_SCREEN", "⚠️ Pointer command missing action")
            return
        }

        Log.v(
            "EDU_SCREEN",
            "🖱️ Pointer $action id=$pointerId type=${command.pointerType} at (${command.x},${command.y}) duration=${command.durationMs}"
        )

        when (action) {
            "DOWN" -> startPointerGesture(pointerId, command, metrics)
            "MOVE" -> enqueuePointerSegment(pointerId, command, metrics, willContinue = true)
            "UP" -> enqueuePointerSegment(pointerId, command, metrics, willContinue = false)
            "CANCEL" -> cancelPointerGesture(pointerId)
            else -> Log.w("EDU_SCREEN", "⚠️ Unknown pointer action: $action")
        }
    }

    private fun startPointerGesture(pointerId: Int, command: RemoteControlCommand, metrics: DisplayMetrics) {
        if (pointerGestures.containsKey(pointerId)) {
            Log.w("EDU_SCREEN", "⚠️ Pointer id=$pointerId already active. Ignoring duplicate DOWN.")
            return
        }

        val x = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
        val y = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
        val duration = command.durationMs.coerceAtLeast(8)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration, true)
        val state = PointerGestureState(stroke, x, y, command.pointerType)
        pointerGestures[pointerId] = state

        Log.d(
            "EDU_SCREEN",
            "🟢 Pointer DOWN id=$pointerId type=${command.pointerType ?: "?"} at (${x.toInt()},${y.toInt()}) pressure=${command.pressure}"
        )
        dispatchStroke(pointerId, state, stroke, x, y, willContinue = true)
    }

    private fun enqueuePointerSegment(
        pointerId: Int,
        command: RemoteControlCommand,
        metrics: DisplayMetrics,
        willContinue: Boolean
    ) {
        val state = pointerGestures[pointerId]
        if (state == null) {
            Log.w("EDU_SCREEN", "⚠️ Pointer state missing for id=$pointerId")
            return
        }

        if (state.pendingDispose && willContinue) {
            Log.w("EDU_SCREEN", "⚠️ Ignoring MOVE for finishing pointer id=$pointerId")
            return
        }

        val x = resolveCoordinate(command.x, command.normalizedX, metrics.widthPixels)
        val y = resolveCoordinate(command.y, command.normalizedY, metrics.heightPixels)
        val duration = command.durationMs.coerceAtLeast(8)

        state.queue.add(PointerSegment(x, y, duration, willContinue))
        if (!willContinue) {
            state.pendingDispose = true
        }

        flushPointerQueue(pointerId, state)
    }

    private fun cancelPointerGesture(pointerId: Int) {
        val state = pointerGestures[pointerId]
        if (state == null) {
            Log.d("EDU_SCREEN", "⛔ Pointer CANCEL id=$pointerId (already released)")
            return
        }

        Log.d(
            "EDU_SCREEN",
            "⛔ Pointer CANCEL id=$pointerId type=${state.pointerType} at (${state.lastX.toInt()},${state.lastY.toInt()})"
        )

        purgePendingTasks(pointerId)
        state.queue.clear()
        state.pendingDispose = true
        state.queue.add(PointerSegment(state.lastX, state.lastY, 16, false))
        if (!state.isDispatching) {
            flushPointerQueue(pointerId, state)
        }
    }

    private fun flushPointerQueue(pointerId: Int, state: PointerGestureState) {
        if (state.isDispatching) return
        if (state.queue.isEmpty()) {
            if (state.pendingDispose) {
                pointerGestures.remove(pointerId)
            }
            return
        }

        val segment = state.queue.removeFirst()
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
            Log.v(
                "EDU_SCREEN",
                "↔️ Pointer MOVE id=$pointerId to (${segment.toX.toInt()},${segment.toY.toInt()}) duration=${segment.duration}"
            )
        } else {
            Log.d(
                "EDU_SCREEN",
                "⬇️ Pointer END id=$pointerId at (${segment.toX.toInt()},${segment.toY.toInt()})"
            )
        }

        dispatchStroke(pointerId, state, continuedStroke, segment.toX, segment.toY, segment.willContinue)
        if (!segment.willContinue && state.queue.isEmpty()) {
            state.pendingDispose = true
        }
    }

    private fun dispatchStroke(
        pointerId: Int,
        state: PointerGestureState,
        stroke: GestureDescription.StrokeDescription,
        endX: Float,
        endY: Float,
        willContinue: Boolean
    ) {
        state.isDispatching = true
        val task = GestureTask(pointerId, stroke, endX, endY, willContinue) { completed ->
            val current = pointerGestures[pointerId]
            if (!completed || current == null) {
                pointerGestures.remove(pointerId)
                purgePendingTasks(pointerId)
                return@GestureTask
            }

            current.stroke = stroke
            current.lastX = endX
            current.lastY = endY
            current.isDispatching = false
            if (!willContinue && current.queue.isEmpty()) {
                pointerGestures.remove(pointerId)
            } else {
                flushPointerQueue(pointerId, current)
            }
        }

        enqueueGestureTask(task)
    }

    private fun enqueueGestureTask(task: GestureTask) {
        gestureQueue.addLast(task)
        processNextGesture()
    }

    private fun purgePendingTasks(pointerId: Int) {
        if (gestureQueue.isEmpty()) return
        val remaining = gestureQueue.filter { it.pointerId != pointerId }
        if (remaining.size == gestureQueue.size) return
        gestureQueue.clear()
        gestureQueue.addAll(remaining)
    }

    private fun processNextGesture() {
        if (gestureInFlight) return
        if (gestureQueue.isEmpty()) return

        val task = gestureQueue.removeFirst()
        gestureInFlight = true

        val success = dispatchGesture(
            GestureDescription.Builder().addStroke(task.stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.post {
                        gestureInFlight = false
                        task.onResult(true)
                        processNextGesture()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    mainHandler.post {
                        gestureInFlight = false
                        Log.w("EDU_SCREEN", "⚠️ Gesture cancelled for pointer ${task.pointerId}")
                        task.onResult(false)
                        processNextGesture()
                    }
                }
            },
            null
        )

        if (!success) {
            mainHandler.post {
                gestureInFlight = false
                Log.e("EDU_SCREEN", "❌ dispatchGesture returned false for pointer ${task.pointerId}")
                task.onResult(false)
                processNextGesture()
            }
        }
    }

    private fun handleKeyboardCommand(command: RemoteControlCommand) {
        val action = command.action?.uppercase()
        when (action) {
            "INSERT_TEXT" -> handleInsertText(command)
            "SET_TEXT" -> setTextOnFocusedNode(command.text ?: "")
            "BACKSPACE" -> removeCharactersFromFocusedNode(1)
            "ENTER" -> appendSpecialText("\n")
            "TAB" -> appendSpecialText("\t")
            else -> Log.w("EDU_SCREEN", "⚠️ Unknown keyboard action: $action")
        }
    }

    private fun handleInsertText(command: RemoteControlCommand) {
        val payload = command.text ?: command.key ?: ""
        if (payload.isEmpty()) {
            Log.w("EDU_SCREEN", "⚠️ INSERT_TEXT received with empty payload")
            return
        }

        modifyFocusedText { existing -> applyTelexInput(existing, payload) }
    }

    private fun appendSpecialText(text: String) {
        if (text.isEmpty()) return
        modifyFocusedText { existing -> existing + text }
    }

    private fun setTextOnFocusedNode(text: String) {
        modifyFocusedText { text }
    }

    private fun removeCharactersFromFocusedNode(count: Int) {
        if (count <= 0) return
        modifyFocusedText { existing ->
            var result = existing
            repeat(count.coerceAtMost(result.codePointCount(0, result.length))) {
                if (result.isEmpty()) return@repeat
                val cutIndex = result.offsetByCodePoints(result.length, -1)
                result = result.substring(0, cutIndex)
            }
            result
        }
    }

    private fun modifyFocusedText(transform: (String) -> String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w("EDU_SCREEN", "⚠️ Text editing requires API 21+")
            return
        }

        val node = getEditableNode()
        if (node == null) {
            Log.w("EDU_SCREEN", "⚠️ No editable node focused; cannot apply text change")
            return
        }

        val hint = node.hintText?.toString()
        val originalRaw = node.text?.toString()
        val original = sanitizeOriginalText(originalRaw, hint)
        val updated = try {
            transform(original)
        } catch (error: Exception) {
            Log.e("EDU_SCREEN", "❌ Error transforming text: ${error.message}", error)
            node.recycle()
            return
        }

        if (updated == null) {
            node.recycle()
            return
        }

        if (updated == original) {
            node.recycle()
            return
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(
            "EDU_SCREEN",
            "⌨️ Text updated success=$success length=${updated.length}"
        )
        node.recycle()
    }

    private fun getEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null) {
            root.recycle()
            return focusedInput
        }

        val focusedAccessibility = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focusedAccessibility != null) {
            if (focusedAccessibility.isEditable) {
                root.recycle()
                return focusedAccessibility
            }
            focusedAccessibility.recycle()
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                if (child.isEditable) {
                    root.recycle()
                    return child
                }
                child.recycle()
            }
        }

        if (root.isEditable) {
            return root
        }

        root.recycle()
        return null
    }
}

private data class VowelInfo(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

private data class VowelKey(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

private data class VowelSet(
    val base: Char,
    val lowercaseForms: List<String>,
    val uppercaseForms: List<String>
)

private val toneKeyMap = mapOf(
    's' to 1,
    'f' to 2,
    'r' to 3,
    'x' to 4,
    'j' to 5
)

private val vowelDecodeMap = HashMap<Char, VowelInfo>()
private val vowelEncodeMap = HashMap<VowelKey, Char>()

private val vowelSets = listOf(
    VowelSet(
        base = 'a',
        lowercaseForms = listOf("aáàảãạ", "âấầẩẫậ", "ăắằẳẵặ"),
        uppercaseForms = listOf("AÁÀẢÃẠ", "ÂẤẦẨẪẬ", "ĂẮẰẲẴẶ")
    ),
    VowelSet(
        base = 'e',
        lowercaseForms = listOf("eéèẻẽẹ", "êếềểễệ"),
        uppercaseForms = listOf("EÉÈẺẼẸ", "ÊẾỀỂỄỆ")
    ),
    VowelSet(
        base = 'i',
        lowercaseForms = listOf("iíìỉĩị"),
        uppercaseForms = listOf("IÍÌỈĨỊ")
    ),
    VowelSet(
        base = 'o',
        lowercaseForms = listOf("oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ"),
        uppercaseForms = listOf("OÓÒỎÕỌ", "ÔỐỒỔỖỘ", "ƠỚỜỞỠỢ")
    ),
    VowelSet(
        base = 'u',
        lowercaseForms = listOf("uúùủũụ", "ưứừửữự"),
        uppercaseForms = listOf("UÚÙỦŨỤ", "ƯỨỪỬỮỰ")
    ),
    VowelSet(
        base = 'y',
        lowercaseForms = listOf("yýỳỷỹỵ"),
        uppercaseForms = listOf("YÝỲỶỸỴ")
    )
)

private fun ensureVowelTables() {
    if (vowelDecodeMap.isNotEmpty()) return

    vowelSets.forEach { set ->
        set.lowercaseForms.forEachIndexed { type, tones ->
            tones.forEachIndexed { tone, ch ->
                vowelDecodeMap[ch] = VowelInfo(set.base, type, tone, false)
                vowelEncodeMap[VowelKey(set.base, type, tone, false)] = ch
            }
        }
        set.uppercaseForms.forEachIndexed { type, tones ->
            tones.forEachIndexed { tone, ch ->
                vowelDecodeMap[ch] = VowelInfo(set.base, type, tone, true)
                vowelEncodeMap[VowelKey(set.base, type, tone, true)] = ch
            }
        }
    }
}

private fun sanitizeOriginalText(original: String?, hint: String?): String {
    val value = original ?: ""
    if (value.isEmpty()) return ""
    val hintValue = hint ?: return value
    return if (value == hintValue) "" else value
}

private fun applyTelexInput(existing: String, input: String): String {
    ensureVowelTables()
    if (input.isEmpty()) return existing
    if (input.length > 1) {
        // Treat multi-character payloads (e.g., paste) as literal text
        return existing + input
    }
    var result = existing
    input.forEach { ch ->
        result = applyTelexChar(result, ch)
    }
    return result
}

private fun applyTelexChar(existing: String, char: Char): String {
    ensureVowelTables()
    val lower = char.lowercaseChar()
    val tone = toneKeyMap[lower]
    if (tone != null) {
        val index = findToneTarget(existing)
        if (index != -1) {
            val info = vowelDecodeMap[existing[index]]
            if (info != null) {
                val replacement = encodeVowel(info.base, info.type, tone, info.uppercase)
                if (replacement != null) {
                    return replaceChar(existing, index, replacement)
                }
            }
        }
        return existing + char
    }

    if (lower == 'z') {
        val index = findToneTarget(existing)
        if (index != -1) {
            val info = vowelDecodeMap[existing[index]]
            if (info != null && info.tone != 0) {
                val replacement = encodeVowel(info.base, info.type, 0, info.uppercase)
                if (replacement != null) {
                    return replaceChar(existing, index, replacement)
                }
            }
        }
        return existing
    }

    if (lower == 'd') {
        val lastIndex = existing.lastIndex
        if (lastIndex >= 0) {
            val lastChar = existing[lastIndex]
            if (lastChar == 'd' || lastChar == 'D') {
                val replacement = if (lastChar.isUpperCase()) 'Đ' else 'đ'
                return replaceChar(existing, lastIndex, replacement)
            }
        }
        return existing + char
    }

    if (lower == 'w') {
        val index = findToneTarget(existing)
        if (index != -1) {
            val info = vowelDecodeMap[existing[index]]
            if (info != null) {
                val newType = when (info.base) {
                    'a' -> 2
                    'o' -> 2
                    'u' -> 1
                    else -> info.type
                }
                if (newType != info.type) {
                    val replacement = encodeVowel(info.base, newType, info.tone, info.uppercase)
                    if (replacement != null) {
                        return replaceChar(existing, index, replacement)
                    }
                }
            }
        }
        return existing + char
    }

    if (lower == 'a' || lower == 'e' || lower == 'o') {
        val lastIndex = existing.lastIndex
        if (lastIndex >= 0) {
            val info = vowelDecodeMap[existing[lastIndex]]
            if (info != null && info.base == lower && info.type == 0) {
                val targetType = when (lower) {
                    'a' -> 1
                    'e' -> 1
                    'o' -> 1
                    else -> info.type
                }
                if (targetType != info.type) {
                    val replacement = encodeVowel(info.base, targetType, info.tone, info.uppercase)
                    if (replacement != null) {
                        return replaceChar(existing, lastIndex, replacement)
                    }
                }
            }
        }
    }

    return existing + char
}

private fun findToneTarget(text: String): Int {
    ensureVowelTables()
    for (index in text.length - 1 downTo 0) {
        if (vowelDecodeMap.containsKey(text[index])) {
            return index
        }
    }
    return -1
}

private fun encodeVowel(base: Char, type: Int, tone: Int, uppercase: Boolean): Char? {
    ensureVowelTables()
    return vowelEncodeMap[VowelKey(base, type, tone, uppercase)]
}

private fun replaceChar(text: String, index: Int, replacement: Char): String {
    if (index < 0 || index >= text.length) return text
    val builder = StringBuilder(text)
    builder.setCharAt(index, replacement)
    return builder.toString()
}
