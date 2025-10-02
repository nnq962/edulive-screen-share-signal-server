# EduLive Screen Share Signal Server
Top 1 worker at Edulive
## Frontend + Backend

1. **Clone repository**
```bash
git clone git@github.com:nnq962/edulive-screen-share-signal-server.git
cd edulive-screen-share-signal-server
```

2. **Cài đặt dependencies**
```bash
npm install
```

3. **Development Mode (2 cổng)**
```bash
# Chạy cả server và client cùng lúc
npm run dev:full

Hoặc chạy riêng lẻ
npm run server  # WebSocket server (port 3001)
npm run dev      # React client (port 3000)
```

4. **Production Mode (1 cổng)**
```bash
# Build và chạy trên cùng một cổng
npm run start
```

5. **Truy cập client**
``` bash
http://localhost:3000
```

## Android app

1. **Mở Android App:** `edulive-screen-share-signal-server/android-app/WebrtcScreenShare`

1. **Thay đổi Gradle JDK:**  
   - `File > Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK > Download JDK`  
   - Chọn **Version: 17**, **Vendor: Microsoft OpenJDK 17.0.16 aarch64**  
   - Sau khi tải xong, đặt `Gradle JDK = ms-17`  

2. **Thay đổi org.gradle.java.home:**  
   - Mở file `gradle.properties`  
   - Sửa `org.gradle.java.home` thành đường dẫn JDK ở bước 1  
   - Ví dụ (macOS):  
     ```properties
     org.gradle.java.home=/Users/quyetnguyen/Library/Java/JavaVirtualMachines/ms-17.0.16/Contents/Home
     ```

3. **Thay đổi config**
    - Khi cài app trên nhiều thiết bị thì cần thay đổi `DEVICE_NAME` và `FIXED_DEVICE_ID` khác nhau trên các thiết bị để tránh xung đột
    - Mở file `WebrtcScreenShare/app/src/main/java/com/codewithkael/webrtcscreenshare/config`
    - `WS_URL:` địa chỉ server
    - `DEVICE_NAME:` Tên thiết bị
    - `FIXED_DEVICE_ID`: ID thiết bị

4. **Sync và build**
