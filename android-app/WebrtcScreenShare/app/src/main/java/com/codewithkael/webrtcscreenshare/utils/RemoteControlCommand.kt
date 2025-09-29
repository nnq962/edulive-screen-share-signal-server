package com.codewithkael.webrtcscreenshare.utils

data class RemoteControlCommand(
    val type: String = "TAP",
    val x: Float = 0f,
    val y: Float = 0f,
    val durationMs: Long = 100,
    val x2: Float? = null,
    val y2: Float? = null
)
