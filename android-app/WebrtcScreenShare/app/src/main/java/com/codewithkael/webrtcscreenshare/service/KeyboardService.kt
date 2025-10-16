package com.codewithkael.webrtcscreenshare.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.codewithkael.webrtcscreenshare.service.keyboard.TextHelper
import com.codewithkael.webrtcscreenshare.utils.RemoteControlCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.O)
object keyboardHelper {

    private var getRootInActiveWindow: (() -> AccessibilityNodeInfo)? = null
    private var clipboardManager: ClipboardManager? = null
    private var applicationContext: Context? = null

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

    // Background sync job for periodic node sync
    private var backgroundTextJob: Job? = null

    // Paste delay job
    private var pasteDelayJob: Job? = null

    // Configuration  
    private const val MAX_POOL_SIZE = 8
    private const val COMBINE_SIZE = 3
    private const val EVENT_CLEANUP_MS = 2000L // Cleanup after 2s (reduced from 5s)
    private const val NODE_SYNC_DELAY_MS = 1000L // Sync with node after 500ms idle
    private const val PASTE_DELAY_MS = 1500L // Delay after paste before allowing next key events

    // Paste delay tracking
    private var lastPasteTime = 0L
    private var isPasteDelayActive = false

    // Enhanced Data class with finalText and timeSend
    data class TextEvent(
        val text: String,                    // Input text
        val action: String,                  // Action type
        val timestamp: Long,                 // Time when event received
        var isSent: Boolean = false,         // Whether event has been sent
        var finalText: String = "",          // Final calculated text result
        var timeSend: Long = 0L,             // Time when event was sent
        val transform: (String) -> String?   // Transformation function
    )

    fun setup(context: Context? = null, cb: () -> AccessibilityNodeInfo ) {
        getRootInActiveWindow = cb
        applicationContext = context
        if (context != null) {
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            Log.d("EDU_SCREEN", "📋 Clipboard manager initialized: ${clipboardManager != null}")
        }
        initializeCurrentText()
    }

    private fun isInPasteDelay(): Boolean {
        if (!isPasteDelayActive) return false
        
        val currentTime = System.currentTimeMillis()
        val timeSincePaste = currentTime - lastPasteTime
        
        if (timeSincePaste >= PASTE_DELAY_MS) {
            isPasteDelayActive = false
            Log.d("EDU_SCREEN", "⏰ Paste delay period ended (${timeSincePaste}ms elapsed)")
            return false
        }
        
        val remainingDelay = PASTE_DELAY_MS - timeSincePaste
        Log.d("EDU_SCREEN", "⏳ Still in paste delay period: ${remainingDelay}ms remaining")
        return true
    }

    private fun startPasteDelayTimer() {
        // Cancel existing paste delay job
        pasteDelayJob?.cancel()
        
        // Start new paste delay job
        pasteDelayJob = processingScope.launch {
            delay(PASTE_DELAY_MS)
            isPasteDelayActive = false
            Log.d("EDU_SCREEN", "⏰ Paste delay timer completed - ready for new key events")
        }
    }

    /**
     * Force reset paste delay - useful for debugging or emergency situations
     */
    fun resetPasteDelay() {
        isPasteDelayActive = false
        pasteDelayJob?.cancel()
        Log.d("EDU_SCREEN", "🔄 Paste delay manually reset")
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

    private fun startBackgroundTextJob(){
        if (backgroundTextJob != null) return // Already running
        backgroundTextJob = processingScope.launch {
            while (isActive){
                delay(200)
                val newNodeText = getNodeText()
                if(newNodeText != null && newNodeText != currentText){
                    Log.e("EDU_SCREEN", "🔄 Background text job difference current text: '$currentText', newNodeText: '$newNodeText'")
                }else{
                    Log.e("EDU_SCREEN", "🔄 Background text job found no change: '$currentText'")
                }
            }
        }
    }

    private fun cancelBackgroundTextJob(){
        backgroundTextJob?.cancel()
        backgroundTextJob = null
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
    fun handleKeyboardCommand(command: RemoteControlCommand) {
        Log.w("handleKeyboardCommand", "command = ${command.dataKey()}")
        val action = command.action?.uppercase()
        
        // Check if we're in paste delay period (except for PASTE action itself)
        if (action != "PASTE" && isInPasteDelay()) {
            Log.w("EDU_SCREEN", "🚫 Ignoring $action command - still in paste delay period")
            return
        }
        
        when (action) {
            "INSERT_TEXT" -> handleInsertText(command)
            "SET_TEXT" -> setTextOnFocusedNode(command.text ?: "")
            "BACKSPACE" -> removeCharactersFromFocusedNode(1)
            "ENTER" -> {
                Log.d("EDU_SCREEN", "🎯 Handling ENTER key - appending newline")
                appendSpecialText("\n")
            }
            "TAB" -> {
                Log.d("EDU_SCREEN", "🎯 Handling TAB key - appending tab")
                appendSpecialText("\t")
            }
            "DELETE" -> {
                Log.d("EDU_SCREEN", "🎯 Handling DELETE key - removing 1 character forward")
                // DELETE key removes character after cursor, but for text input fields
                // we'll treat it the same as BACKSPACE for simplicity
                removeCharactersFromFocusedNode(1)
            }
            "COPY" -> {
                Log.d("EDU_SCREEN", "🎯 Handling COPY - copying selected text to clipboard")
                handleCopyText()
            }
            "PASTE" -> {
                Log.d("EDU_SCREEN", "🎯 Handling PASTE - pasting text from clipboard")
                handlePasteText(command.text)
            }
            "CUT" -> {
                Log.d("EDU_SCREEN", "🎯 Handling CUT - cutting selected text to clipboard")
                handleCutText()
            }
            "SELECT_ALL" -> {
                Log.d("EDU_SCREEN", "🎯 Handling SELECT_ALL - selecting all text")
                handleSelectAllText()
            }
            "ARROW_UP", "ARROW_DOWN", "ARROW_LEFT", "ARROW_RIGHT" -> {
                Log.d("EDU_SCREEN", "🎯 Handling arrow key: $action - not supported in text mode")
                // Arrow keys are typically handled by the system, not by text insertion
                // In text editing context, we might want to move cursor but that's complex
                // For now, we log and ignore
            }
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
            TextHelper.applyTelexInput(existing, payload)
        }
    }

    private fun appendSpecialText(text: String) {
        if (text.isEmpty()) {
            Log.w("EDU_SCREEN", "⚠️ appendSpecialText called with empty text")
            return
        }
        
        val displayText = when (text) {
            "\n" -> "\\n (newline)"
            "\t" -> "\\t (tab)"
            else -> "\"$text\""
        }
        Log.d("EDU_SCREEN", "📝 Appending special text: $displayText")
        
        enqueueTextEvent(text, "APPEND") { existing -> 
            val result = existing + text
            Log.d("EDU_SCREEN", "📝 Text after append: length=${result.length}, ends with newline: ${result.endsWith("\n")}")
            result
        }
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

    private fun handleCopyText() {
        try {
            val node = getEditableNode()
            if (node == null) {
                Log.w("EDU_SCREEN", "⚠️ No editable node focused; cannot copy text")
                return
            }

            val selectedText = getSelectedText(node)
            if (selectedText.isNullOrEmpty()) {
                Log.w("EDU_SCREEN", "⚠️ No text selected for copy operation")
                node.recycle()
                return
            }

            copyToClipboard(selectedText, "Copied Text")
            Log.d("EDU_SCREEN", "📋 Copied text to clipboard: '${selectedText.take(50)}${if (selectedText.length > 50) "..." else ""}'")
            node.recycle()
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error copying text: ${e.message}")
        }
    }

    private fun handlePasteText(pasteText: String?) {
        try {
            val textToPaste = pasteText ?: getClipboardText()
            if (textToPaste.isNullOrEmpty()) {
                Log.w("EDU_SCREEN", "⚠️ No text available to paste")
                return
            }

            Log.d("EDU_SCREEN", "📋 Pasting text: '${textToPaste.take(50)}${if (textToPaste.length > 50) "..." else ""}' (${textToPaste.length} chars)")
            
            // Start paste delay period
            lastPasteTime = System.currentTimeMillis()
            isPasteDelayActive = true
            startPasteDelayTimer()
            Log.d("EDU_SCREEN", "⏰ Started paste delay period: ${PASTE_DELAY_MS}ms")
            
            // Use INSERT_TEXT to handle paste, which will apply Telex if needed
            enqueueTextEvent(textToPaste, "PASTE") { existing ->
                // For paste, we typically want to insert at cursor position
                // Since we don't have cursor position, we append for now
                // In a more advanced implementation, we'd replace selected text or insert at cursor
                existing + textToPaste
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error pasting text: ${e.message}")
            // Reset paste delay on error
            isPasteDelayActive = false
            pasteDelayJob?.cancel()
        }
    }

    private fun handleCutText() {
        try {
            val node = getEditableNode()
            if (node == null) {
                Log.w("EDU_SCREEN", "⚠️ No editable node focused; cannot cut text")
                return
            }

            val selectedText = getSelectedText(node)
            if (selectedText.isNullOrEmpty()) {
                Log.w("EDU_SCREEN", "⚠️ No text selected for cut operation")
                node.recycle()
                return
            }

            // Copy to clipboard first
            copyToClipboard(selectedText, "Cut Text")
            
            // Then delete the selected text
            // This is a simplified implementation - in reality we'd need to handle selection properly
            val fullText = node.text?.toString() ?: ""
            val newText = fullText.replace(selectedText, "")
            
            enqueueTextEvent(newText, "CUT") { _ -> newText }
            
            Log.d("EDU_SCREEN", "✂️ Cut text to clipboard: '${selectedText.take(50)}${if (selectedText.length > 50) "..." else ""}'")
            node.recycle()
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error cutting text: ${e.message}")
        }
    }

    private fun handleSelectAllText() {
        try {
            val node = getEditableNode()
            if (node == null) {
                Log.w("EDU_SCREEN", "⚠️ No editable node focused; cannot select all text")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val fullText = node.text?.toString() ?: ""
                if (fullText.isNotEmpty()) {
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, fullText.length)
                    }
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                    Log.d("EDU_SCREEN", "📝 Select all text: ${if (success) "success" else "failed"} (${fullText.length} chars)")
                } else {
                    Log.w("EDU_SCREEN", "⚠️ No text to select")
                }
            } else {
                Log.w("EDU_SCREEN", "⚠️ Select all requires API 18+")
            }
            
            node.recycle()
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error selecting all text: ${e.message}")
        }
    }

    private fun getSelectedText(node: AccessibilityNodeInfo): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val selectionStart = node.textSelectionStart
                val selectionEnd = node.textSelectionEnd
                val fullText = node.text?.toString()
                
                if (fullText != null && selectionStart >= 0 && selectionEnd > selectionStart && 
                    selectionStart < fullText.length && selectionEnd <= fullText.length) {
                    fullText.substring(selectionStart, selectionEnd)
                } else {
                    // If no selection, return the full text as fallback
                    fullText
                }
            } else {
                // For older API versions, return full text
                node.text?.toString()
            }
        } catch (e: Exception) {
            Log.w("EDU_SCREEN", "⚠️ Error getting selected text: ${e.message}")
            null
        }
    }

    private fun copyToClipboard(text: String, label: String = "Copied Text") {
        try {
            val clipboard = clipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText(label, text)
                clipboard.setPrimaryClip(clip)
                Log.d("EDU_SCREEN", "📋 Text copied to clipboard successfully")
            } else {
                Log.w("EDU_SCREEN", "⚠️ Clipboard manager not available")
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error copying to clipboard: ${e.message}")
        }
    }

    private fun getClipboardText(): String? {
        return try {
            val clipboard = clipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    val text = item.text?.toString()
                    Log.d("EDU_SCREEN", "📋 Retrieved text from clipboard: ${text?.length ?: 0} chars")
                    text
                } else {
                    Log.w("EDU_SCREEN", "⚠️ No clipboard content available")
                    null
                }
            } else {
                Log.w("EDU_SCREEN", "⚠️ Clipboard manager not available or no content")
                null
            }
        } catch (e: Exception) {
            Log.e("EDU_SCREEN", "❌ Error reading from clipboard: ${e.message}")
            null
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
            } else {
                Log.e("EDU_SCREEN", "➡️ Processing ${unsentEvents.size} queued events")
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

    private fun getNodeText(nodeD: AccessibilityNodeInfo? = null): String? {
        return try {
            val node = nodeD?: getEditableNode()
            if (node != null) {
                val hint = node.hintText?.toString()
                val originalRaw = node.text?.toString()
                val result = TextHelper.sanitizeOriginalText(originalRaw, hint)
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
                    val insertedText = event.text
                    if (insertedText.isNotEmpty()) {
                        // Move cursor to after the inserted text
                        newPosition = currentSelection + insertedText.codePointCount(0, insertedText.length)
                    } else {
                        newPosition = newText.length
                    }
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
                    // For append (like ENTER, TAB), calculate position based on appended text
                    val appendedText = event.text
                    if (appendedText.isNotEmpty()) {
                        // Move cursor to after the appended text
                        newPosition = currentSelection + appendedText.codePointCount(0, appendedText.length)
                    } else {
                        newPosition = newText.length
                    }
                }

                event.action == "PASTE" -> {
                    // For paste, move cursor to end of pasted text
                    val pastedText = event.text
                    if (pastedText.isNotEmpty()) {
                        newPosition = currentSelection + pastedText.codePointCount(0, pastedText.length)
                    } else {
                        newPosition = newText.length
                    }
                }

                event.action == "CUT" -> {
                    // For cut, the new text should be shorter, so position might need adjustment
                    newPosition = newText.length.coerceAtMost(currentSelection)
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




    fun cleanup() {
        backgroundSyncJob?.cancel()
        backgroundTextJob?.cancel()
        pasteDelayJob?.cancel()
        processingScope.cancel()
        clipboardManager = null
        applicationContext = null
        isPasteDelayActive = false
        lastPasteTime = 0L
        Log.d("EDU_SCREEN", "🧹 KeyboardHelper cleanup completed")
    }
}