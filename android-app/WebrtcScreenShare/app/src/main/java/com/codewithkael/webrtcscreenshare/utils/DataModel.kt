package com.codewithkael.webrtcscreenshare.utils

enum class DataModelType{
    DEVICE_REGISTER, DEVICE_START_STREAM, DEVICE_STOP_STREAM, OFFER, ANSWER, ICE_CANDIDATE, DEVICE_SCREEN_INFO
}

data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val type: String = "android-device",
    val screenWidth: Int? = null,
    val screenHeight: Int? = null
)

data class DataModel(
    val type: String? = null,
    val deviceId: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val targetDeviceId: String? = null,
    val viewerId: String? = null,
    val offer: Any? = null,
    val answer: Any? = null,
    val candidate: Any? = null,
    val data: Any? = null,
    val message: String? = null
)
