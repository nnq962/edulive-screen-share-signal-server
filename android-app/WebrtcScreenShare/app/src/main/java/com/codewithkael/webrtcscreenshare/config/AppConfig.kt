package com.codewithkael.webrtcscreenshare.config

object AppConfig {
    // ĐỊA CHỈ WEBSOCKET MẶC ĐỊNH (chỉnh tại đây)
    const val WS_URL: String = "ws://192.168.1.91:3001/ws"

    // TÊN THIẾT BỊ MẶC ĐỊNH (chỉnh tại đây)
    const val DEVICE_NAME: String = "Android Device SAMSUNG"

    // ID THIẾT BỊ CỐ ĐỊNH (tùy chọn). Để rỗng "" thì app sẽ tự lưu một ID bền vững lần đầu chạy
    const val FIXED_DEVICE_ID: String = "android1"
}