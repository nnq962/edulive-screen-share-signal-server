package com.codewithkael.webrtcscreenshare.service

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand
import kotlinx.coroutines.*
import kotlin.collections.set

@RequiresApi(Build.VERSION_CODES.O)
object keyboardHelper {
    private val toneKeyMap = mapOf(
        's' to 1,
        'f' to 2,
        'r' to 3,
        'x' to 4,
        'j' to 5
    )

    private val vowelDecodeMap = HashMap<Char, VowelInfo>()
    private val vowelEncodeMap = HashMap<VowelKey, Char>()

    private var getRootInActiveWindow: (() -> AccessibilityNodeInfo)? = null

    // Coroutine-based Queue Text Processing System
    private val eventQueue = mutableListOf<TextEvent>()
    private val queueLock = Any()
    private var currentText = ""
    private var isProcessing = false
    private var lastSentEventTime = 0L

    // Coroutine scope for async processing
    private val processingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Background sync job for periodic node sync
    private var backgroundSyncJob: Job? = null
    
    // Configuration  
    private const val MAX_POOL_SIZE = 8
    private const val COMBINE_SIZE = 3
    private const val EVENT_CLEANUP_MS = 2000L // Cleanup after 2s (reduced from 5s)
    private const val NODE_SYNC_DELAY_MS = 500L // Sync with node after 500ms idle    // Enhanced Data class with finalText and timeSend
    data class TextEvent(
        val text: String,                    // Input text
        val action: String,                  // Action type
        val timestamp: Long,                 // Time when event received
        var isSent: Boolean = false,         // Whether event has been sent
        var finalText: String = "",          // Final calculated text result
        var timeSend: Long = 0L,             // Time when event was sent
        val transform: (String) -> String?   // Transformation function
    )

    fun setup(cb: () -> AccessibilityNodeInfo) {
        getRootInActiveWindow = cb
        initializeCurrentText()
    }
    
    private fun startBackgroundSync() {
        // Cancel existing background sync job
        backgroundSyncJob?.cancel()
        
        // Start new single-time delay sync job
        backgroundSyncJob = processingScope.launch {
            delay(NODE_SYNC_DELAY_MS) // Wait for 500ms delay
            syncWithNodeText()
        }
    }
    
    private fun initializeCurrentText() {
        processingScope.launch {
            try {
                val nodeText = withContext(Dispatchers.Main) {
                    getNodeText()
                }
                if (nodeText != null) {
                    currentText = nodeText
                    Log.d("EDU_SCREEN", "🔄 Initialized current text: '$currentText'")
                }
            } catch (e: Exception) {
                Log.w("EDU_SCREEN", "⚠️ Could not initialize current text: ${e.message}")
                currentText = ""
            }
        }
    }

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


    fun handleKeyboardCommand(command: RemoteControlCommand) {
        Log.w("handleKeyboardCommand", "command = ${command.dataKey()}")
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

    fun RemoteControlCommand.dataKey(): String {
        return "Command ${this.action ?: ""} text=${this.text} text=${this.text}  key=${this.key} keyCode=${this.keyCode} altKey=${this.altKey}  shiftKey=${this.shiftKey}  ctrlKey=${this.ctrlKey}  metaKey=${this.metaKey} })"
    }

    private fun handleInsertText(command: RemoteControlCommand) {
        val payload = command.text ?: command.key ?: ""
        if (payload.isEmpty()) {
            Log.w("EDU_SCREEN", "⚠️ INSERT_TEXT received with empty payload")
            return
        }

        enqueueTextEvent(payload, "INSERT_TEXT") { existing ->
            applyTelexInput(existing, payload)
        }
    }

    private fun appendSpecialText(text: String) {
        if (text.isEmpty()) return
        enqueueTextEvent(text, "APPEND") { existing -> existing + text }
    }

    private fun setTextOnFocusedNode(text: String) {
        enqueueTextEvent(text, "SET_TEXT") { text }
    }

    private fun removeCharactersFromFocusedNode(count: Int) {
        if (count <= 0) return
        enqueueTextEvent("", "BACKSPACE_$count") { existing ->
            var result = existing
            repeat(count.coerceAtMost(result.codePointCount(0, result.length))) {
                if (result.isEmpty()) return@repeat
                val cutIndex = result.offsetByCodePoints(result.length, -1)
                result = result.substring(0, cutIndex)
            }
            result
        }
    }

    private fun enqueueTextEvent(text: String, action: String, transform: (String) -> String?) {
        synchronized(queueLock) {
            val event = TextEvent(
                text = text,
                action = action,
                timestamp = System.currentTimeMillis(),
                transform = transform
            )

            eventQueue.add(event)
            Log.d(
                "EDU_SCREEN",
                "📥 Event queued: $action, text='$text', queue size: ${eventQueue.size}"
            )

            // Clean up old events
            cleanupOldEvents()

            // Start processing if not already running (using coroutines)
            if (!isProcessing) {
                processingScope.launch {
                    processEventQueue()
                }
            }
        }
    }

    private fun cleanupOldEvents() {
        val currentTime = System.currentTimeMillis()
        val iterator = eventQueue.iterator()
        var removedCount = 0

        while (iterator.hasNext()) {
            val event = iterator.next()
            // Clean up events that were sent and are older than 2s
            if (event.isSent && event.timeSend > 0 && (currentTime - event.timeSend) > EVENT_CLEANUP_MS) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            Log.d(
                "EDU_SCREEN",
                "🧹 Cleaned up $removedCount old events, queue size: ${eventQueue.size}"
            )
        }
    }

    private suspend fun processEventQueue() {
        synchronized(queueLock) {
            if (isProcessing || eventQueue.isEmpty()) return
            isProcessing = true
        }

        try {
            val unsentEvents = synchronized(queueLock) {
                eventQueue.filter { !it.isSent }
            }
            // Special case: If only 1 event, sync with node first
            if (unsentEvents.size <= 1) {
                Log.d("EDU_SCREEN", "🔄 Single event detected, syncing with node text first")
                syncWithNodeText()
            }

            // Calculate final text based on current strategy
            val finalText = calculateFinalText(unsentEvents)

            // Execute the text update on main thread
            withContext(Dispatchers.Main) {
                executeTextUpdate(finalText, unsentEvents)
                // Schedule background sync after processing is complete
                startBackgroundSync()
            }

            // Mark events as sent and update timestamps
            val sendTime = System.currentTimeMillis()
            synchronized(queueLock) {
                unsentEvents.forEach { event ->
                    event.isSent = true
                    event.finalText = finalText
                    event.timeSend = sendTime
                }
                lastSentEventTime = sendTime
            }

            Log.d(
                "EDU_SCREEN",
                "✅ Processed ${unsentEvents.size} events, final text length: ${finalText.length}"
            )

            // Continue processing if there are more events
            val hasMoreEvents = synchronized(queueLock) {
                eventQueue.any { !it.isSent }
            }

            if (hasMoreEvents) {
                // Schedule next batch processing with small delay
                delay(10) // Small delay to prevent overwhelming
                processEventQueue()
            } else {
                synchronized(queueLock) { isProcessing = false }
            }

        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error processing event queue: ${e.message}", e)
            synchronized(queueLock) { isProcessing = false }
        }
    }

    private fun calculateFinalText(events: List<TextEvent>): String {
        val unsentEvents = synchronized(queueLock) {
            eventQueue.filter { !it.isSent }
        }
        val currentTime = System.currentTimeMillis()

        // Strategy 2.1: If there are unsent events, use previous event's finalText or currentText
        val baseText = if (unsentEvents.isNotEmpty()) {
            Log.d(
                "EDU_SCREEN",
                "🧮 Using queue-based calculation (${unsentEvents.size} unsent events)"
            )

            // Find the last sent event to get its finalText
            val lastSentEvent = synchronized(queueLock) {
                eventQueue.filter { it.isSent && it.finalText.isNotEmpty() }
                    .maxByOrNull { it.timeSend }
            }

            if (lastSentEvent != null) {
                Log.d(
                    "EDU_SCREEN",
                    "📋 Using previous event finalText: '${lastSentEvent.finalText}'"
                )
                lastSentEvent.finalText
            } else {
                Log.d("EDU_SCREEN", "📋 Using current tracked text: '$currentText'")
                currentText
            }
        }
        // Strategy 2.2: If queue empty and last event was sent >500ms ago, sync with node
        else if (lastSentEventTime > 0 && (currentTime - lastSentEventTime) > NODE_SYNC_DELAY_MS) {
            Log.d("EDU_SCREEN", "🔄 Syncing with node text (idle >500ms)")
            val nodeText = getNodeText()
            if (nodeText != null) {
                currentText = nodeText
                Log.d("EDU_SCREEN", "📱 Node text synced: '$currentText'")
            }
            currentText
        }
        // Default: use current tracked text
        else {
            Log.d("EDU_SCREEN", "🧮 Using tracked current text")
            currentText
        }

        var result = baseText
        Log.d(
            "EDU_SCREEN",
            "🧮 Calculating final text from ${events.size} events, starting with: '$result'"
        )

        events.forEach { event ->
            try {
                val newResult = event.transform(result)
                if (newResult != null) {
                    result = newResult
                    Log.d("EDU_SCREEN", "  → ${event.action}: '$result'")
                }
            } catch (e: Exception) {
                Log.e("EDU_SCREEN", "❌ Error applying transform for ${event.action}: ${e.message}")
            }
        }

        return result
    }

    private suspend fun syncWithNodeText() {
        Log.d("EDU_SCREEN", "🔄 syncWithNodeText triggered")
        try {
            val nodeText = withContext(Dispatchers.Main) {
                getNodeText()
            }
            if (nodeText != null && nodeText != currentText) {
                Log.d("EDU_SCREEN", "🔄 Syncing currentText with node: '$currentText' → '$nodeText'")
                currentText = nodeText
            } else if (nodeText != null) {
                Log.d("EDU_SCREEN", "✅ Node text already in sync: '$nodeText'")
            } else {
                Log.w("EDU_SCREEN", "⚠️ Could not read node text for sync")
            }
        } catch (e: Exception) {
            Log.w("EDU_SCREEN", "⚠️ Could not sync with node text: ${e.message}")
        }
    }

    private fun getNodeText(): String? {
        return try {
            val node = getEditableNode()
            if (node != null) {
                val hint = node.hintText?.toString()
                val originalRaw = node.text?.toString()
                val result = sanitizeOriginalText(originalRaw, hint)
                node.recycle()
                result
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("EDU_SCREEN", "⚠️ Error reading node text: ${e.message}")
            null
        }
    }

    @SuppressLint("NewApi")
    private fun executeTextUpdate(finalText: String, processedEvents: List<TextEvent>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w("EDU_SCREEN", "⚠️ Text editing requires API 21+")
            return
        }

        val node = getEditableNode()
        if (node == null) {
            Log.w("EDU_SCREEN", "⚠️ No editable node focused; cannot apply text change")
            return
        }

        // Store current selection position
        val selectionStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.textSelectionStart
        } else {
            -1
        }

        // Only update if text actually changed
        if (finalText == currentText) {
            Log.d("EDU_SCREEN", "📝 Text unchanged, skipping update")
            node.recycle()
            return
        }

        // Calculate new selection position based on the transformation
        val newSelectionStart =
            calculateSelectionPosition(currentText, finalText, selectionStart, processedEvents)

        // Set the new text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (success) {
            // Update our tracked current text
            currentText = finalText

            // Try to restore selection position
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                try {
                    val selectionArgs = Bundle().apply {
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                            newSelectionStart
                        )
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                            newSelectionStart
                        )
                    }
                    val selectionSuccess = node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        selectionArgs
                    )
                    Log.d(
                        "EDU_SCREEN",
                        "📍 Selection set: $newSelectionStart (success: $selectionSuccess)"
                    )
                } catch (e: Exception) {
                    Log.w("EDU_SCREEN", "⚠️ Could not set selection: ${e.message}")
                }
            }

            Log.d(
                "EDU_SCREEN",
                "✅ Text updated successfully: length=${finalText.length}, events=${processedEvents.size}"
            )
        } else {
            Log.e("EDU_SCREEN", "❌ Failed to update text")
        }

        node.recycle()
    }

    private fun calculateSelectionPosition(
        oldText: String,
        newText: String,
        currentSelection: Int,
        events: List<TextEvent>
    ): Int {
        // If no selection info, default to end
        if (currentSelection < 0) {
            return newText.length
        }

        // Calculate position based on event types
        var newPosition = currentSelection

        events.forEach { event ->
            when {
                event.action.startsWith("INSERT_TEXT") -> {
                    // For insertions, move cursor to end of inserted text
                    newPosition = newText.length
                }

                event.action.startsWith("BACKSPACE") -> {
                    // For backspace, adjust position based on deleted characters
                    val deletedCount = event.action.substringAfter("_").toIntOrNull() ?: 1
                    newPosition = (currentSelection - deletedCount).coerceAtLeast(0)
                }

                event.action == "SET_TEXT" -> {
                    // For set text, move to end
                    newPosition = newText.length
                }

                event.action == "APPEND" -> {
                    // For append, move to end
                    newPosition = newText.length
                }
            }
        }

        // Ensure position is within bounds
        return newPosition.coerceIn(0, newText.length)
    }

    private fun getEditableNode(): AccessibilityNodeInfo? {
        val root = getRootInActiveWindow?.invoke() ?: return null

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


    ////
    private fun findToneTarget(text: String): Int {
        ensureVowelTables()
        
        // Find the last space to get the current word boundary
        val lastSpaceIndex = text.lastIndexOf(' ')
        val startIndex = if (lastSpaceIndex == -1) 0 else lastSpaceIndex + 1
        
        // Only search in the current word (after the last space)
        for (index in text.length - 1 downTo startIndex) {
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
                    val replacement = encodeVowel(
                        info.base,
                        info.type,
                        tone,
                        info.uppercase
                    )
                    if (replacement != null) {
                        return replaceChar(
                            existing,
                            index,
                            replacement
                        )
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
                    val replacement = encodeVowel(
                        info.base,
                        info.type,
                        0,
                        info.uppercase
                    )
                    if (replacement != null) {
                        return replaceChar(
                            existing,
                            index,
                            replacement
                        )
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
                    return replaceChar(
                        existing,
                        lastIndex,
                        replacement
                    )
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
                        val replacement = encodeVowel(
                            info.base,
                            newType,
                            info.tone,
                            info.uppercase
                        )
                        if (replacement != null) {
                            return replaceChar(
                                existing,
                                index,
                                replacement
                            )
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
                        val replacement = encodeVowel(
                            info.base,
                            targetType,
                            info.tone,
                            info.uppercase
                        )
                        if (replacement != null) {
                            return replaceChar(
                                existing,
                                lastIndex,
                                replacement
                            )
                        }
                    }
                }
            }
        }

        return existing + char
    }
    
    fun cleanup() {
        backgroundSyncJob?.cancel()
        processingScope.cancel()
        Log.d("EDU_SCREEN", "🧹 KeyboardHelper cleanup completed")
    }
}

data class VowelInfo(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

data class VowelKey(
    val base: Char,
    val type: Int,
    val tone: Int,
    val uppercase: Boolean
)

data class VowelSet(
    val base: Char,
    val lowercaseForms: List<String>,
    val uppercaseForms: List<String>
)