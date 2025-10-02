package com.codewithkael.webrtcscreenshare.utils

data class RemoteControlCommand(
    val type: String = "TAP",
    val x: Float = 0f,
    val y: Float = 0f,
    val durationMs: Long = 100,
    val x2: Float? = null,
    val y2: Float? = null,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val normalizedX2: Float? = null,
    val normalizedY2: Float? = null,
    val pointerId: Int? = null,
    val action: String? = null,
    val pointerType: String? = null,
    val pressure: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val text: String? = null,
    val key: String? = null,
    val code: String? = null,
    val keyCode: Int? = null,
    val altKey: Boolean? = null,
    val ctrlKey: Boolean? = null,
    val shiftKey: Boolean? = null,
    val metaKey: Boolean? = null
)
