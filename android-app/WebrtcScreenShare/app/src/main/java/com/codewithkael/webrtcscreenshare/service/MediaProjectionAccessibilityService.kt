package com.codewithkael.webrtcscreenshare.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MediaProjectionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MediaProjectionA11y"
        var isEnabled = false
        
        // Các text có thể có cho button Cancel
        private val CANCEL_BUTTON_TEXTS = arrayOf(
            "Hủy",      // Tiếng Việt
            "Cancel",   // English
            "取消",      // Chinese
            "キャンセル",  // Japanese
            "취소"       // Korean
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hasProcessedDialog = false
    private var attemptCount = 0
    private var lastDialogTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isEnabled = true
        Log.d(TAG, "✅ MediaProjectionAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: ""
            
            if (packageName.contains("systemui")) {
                val currentTime = System.currentTimeMillis()
                
                // Chỉ xử lý nếu đây là dialog mới (tránh duplicate events)
                if (currentTime - lastDialogTime > 500) {
                    Log.d(TAG, "📱 Media Projection dialog detected")
                    hasProcessedDialog = false
                    attemptCount = 0
                    lastDialogTime = currentTime
                    
                    // Thử click ngay sau 10ms
                    handler.postDelayed({ tryAutoClick() }, 10)
                }
            }
        }
    }

    private fun tryAutoClick() {
        if (hasProcessedDialog) {
            return
        }

        attemptCount++
        
        val windows = windows
        var foundDialog = false
        
        windows?.forEach { window ->
            if (window.isActive && isMediaProjectionDialog(window.title?.toString())) {
                foundDialog = true
                
                window.root?.let { root ->
                    val cancelButton = findCancelButton(root)
                    
                    if (cancelButton != null) {
                        val bounds = Rect()
                        cancelButton.getBoundsInScreen(bounds)
                        
                        // Tính toán vị trí button "Bắt đầu ngay"/"Start now" (đối xứng với button Hủy/Cancel)
                        val screenWidth = resources.displayMetrics.widthPixels
                        val cancelDistanceFromLeft = bounds.centerX()
                        val startButtonX = screenWidth - cancelDistanceFromLeft
                        val startButtonY = bounds.centerY()
                        
                        Log.d(TAG, "🎯 Attempt #$attemptCount - Found cancel button at (${bounds.centerX()}, ${bounds.centerY()})")
                        Log.d(TAG, "   Calculated start button position: ($startButtonX, $startButtonY)")
                        
                        cancelButton.recycle()
                        
                        // Click vào button "Bắt đầu ngay"/"Start now"
                        clickAtCoordinate(startButtonX, startButtonY)
                        hasProcessedDialog = true
                        
                        return
                    }
                }
            }
        }
        
        // Chỉ retry nếu vẫn còn thấy dialog và chưa quá 5 lần
        if (!hasProcessedDialog && foundDialog && attemptCount < 5) {
            handler.postDelayed({ tryAutoClick() }, 50)
        } else if (!foundDialog) {
            // Dialog đã đóng, dừng retry
            hasProcessedDialog = true
        }
    }

    /**
     * Kiểm tra xem có phải Media Projection dialog không
     * Hỗ trợ nhiều ngôn ngữ
     */
    private fun isMediaProjectionDialog(title: String?): Boolean {
        if (title == null) return false
        
        return title.contains("Bắt đầu", ignoreCase = true) ||
               title.contains("Start", ignoreCase = true) ||
               title.contains("screen", ignoreCase = true) ||
               title.contains("recording", ignoreCase = true) ||
               title.contains("cast", ignoreCase = true) ||
               title.contains("ghi", ignoreCase = true)
    }

    /**
     * Tìm button Cancel/Hủy trong cây node
     * Hỗ trợ nhiều ngôn ngữ
     */
    private fun findCancelButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        
        // Kiểm tra xem text có match với bất kỳ cancel button text nào không
        if (CANCEL_BUTTON_TEXTS.any { it.equals(text, ignoreCase = true) }) {
            Log.d(TAG, "   ✅ Found cancel button with text: '$text'")
            return node
        }
        
        // Duyệt đệ quy qua các child nodes
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val result = findCancelButton(child)
                if (result != null) {
                    child.recycle()
                    return result
                }
                child.recycle()
            }
        }
        
        return null
    }

    /**
     * Click tại tọa độ chỉ định
     */
    private fun clickAtCoordinate(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        
        val gestureBuilder = GestureDescription.Builder()
        // Click nhanh 100ms
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        
        val gesture = gestureBuilder.build()
        
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "✅ Auto-accepted Media Projection successfully!")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "❌ Click gesture cancelled")
            }
        }, null)
        
        return result
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "❌ Service destroyed")
    }
}
