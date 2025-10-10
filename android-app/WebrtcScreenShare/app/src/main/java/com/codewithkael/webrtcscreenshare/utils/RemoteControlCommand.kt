package com.codewithkael.webrtcscreenshare.utils

data class RemoteControlCommand(
    // Command type
    val type: String = "TAP",
    
    // Position data
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float? = null,
    val y2: Float? = null,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val normalizedX2: Float? = null,
    val normalizedY2: Float? = null,
    
    // Timing
    val durationMs: Long = 100,
    val timestamp: Long? = null,
    val eventTime: Long? = null,
    val downTime: Long? = null,
    
    // Pointer identification
    val pointerId: Int? = null,
    val pointerIndex: Int? = null,
    val pointerCount: Int? = null,
    val action: String? = null,
    val pointerType: String? = null,
    
    // Touch characteristics (matching MotionEvent)
    val pressure: Float? = null,
    val size: Float? = null,
    val touchMajor: Float? = null,
    val touchMinor: Float? = null,
    val toolMajor: Float? = null,
    val toolMinor: Float? = null,
    val orientation: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    
    // Additional pointer data
    val tiltX: Float? = null,
    val tiltY: Float? = null,
    val twist: Float? = null,
    
    // Keyboard data
    val text: String? = null,
    val key: String? = null,
    val code: String? = null,
    val keyCode: Int? = null,
    val altKey: Boolean? = null,
    val ctrlKey: Boolean? = null,
    val shiftKey: Boolean? = null,
    val metaKey: Boolean? = null
)
