package com.codewithkael.webrtcscreenshare.utils

enum class DataModelType{
    DEVICE_REGISTER, DEVICE_START_STREAM, DEVICE_STOP_STREAM, OFFER, ANSWER, ICE_CANDIDATE
}

data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val type: String = "android-device"
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
