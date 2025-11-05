# Server Folder - WebSocket Signaling Server

## Tổng quan

Thư mục `server` chứa WebSocket signaling server cho ứng dụng chia sẻ màn hình (screen sharing) sử dụng công nghệ WebRTC. Server đóng vai trò trung gian kết nối giữa các thiết bị Android (người chia sẻ màn hình) và web viewers (người xem màn hình).

## Cấu trúc File

### 1. `dev.js` - Development Server
**Mục đích:** Server phát triển, chỉ chạy WebSocket server riêng biệt.

**Đặc điểm:**
- Chạy trên port 3001
- Không serve frontend (frontend chạy riêng trên Vite dev server port 3000)
- Sử dụng khi development: `npm run server`
- Hỗ trợ CORS cho localhost:3000 và 127.0.0.1:3000

**Khi nào dùng:**
```bash
# Chạy riêng server và client trên 2 cổng khác nhau
npm run dev:full  # Hoặc
npm run server    # Server (port 3001)
npm run dev       # Client (port 3000)
```

### 2. `index.js` - Production Server
**Mục đích:** Server production, serve cả frontend và backend trên cùng một port.

**Đặc điểm:**
- Chạy trên port 3001 (hoặc PORT từ environment variable)
- Serve static files từ thư mục `dist` (frontend đã build)
- Tích hợp cả WebSocket server và HTTP server
- Sử dụng khi production: `npm run start`

**Khi nào dùng:**
```bash
# Build và chạy production mode trên cùng 1 cổng
npm run start
```

## Chức năng chính

### 1. WebSocket Server
Server sử dụng WebSocket (thư viện `ws`) để duy trì kết nối real-time giữa devices và viewers.

**WebSocket Endpoint:** `ws://localhost:3001/ws`

### 2. Quản lý Connections

#### Devices (Thiết bị Android)
Server quản lý danh sách các thiết bị Android đang kết nối:
```javascript
devices = Map {
  deviceId -> {
    connection: WebSocket,
    deviceInfo: {
      id: string,
      name: string,
      type: 'android',
      isConnected: boolean,
      lastSeen: timestamp,
      isStreaming: boolean,
      screenWidth: number,
      screenHeight: number
    }
  }
}
```

#### Viewers (Người xem)
Server quản lý danh sách viewers đang kết nối:
```javascript
viewers = Map {
  viewerId -> {
    connection: WebSocket,
    activeDevices: Set<deviceId>
  }
}
```

### 3. Message Types (Các loại tin nhắn)

Server xử lý các loại message sau:

#### Device Messages (Từ thiết bị)
- `DEVICE_REGISTER` - Đăng ký thiết bị mới
- `DEVICE_START_STREAM` - Bắt đầu stream màn hình
- `DEVICE_STOP_STREAM` - Dừng stream màn hình
- `HEARTBEAT` - Gửi heartbeat để duy trì kết nối
- `DEVICE_SCREEN_INFO` - Thông tin kích thước màn hình

#### Viewer Messages (Từ người xem)
- `VIEWER_JOIN` - Viewer tham gia
- `VIEWER_LEAVE` - Viewer rời đi
- `REQUEST_STREAM` - Yêu cầu xem stream từ device
- `STOP_STREAM` - Dừng xem stream
- `CONTROL_COMMAND` - Gửi lệnh điều khiển đến device

#### WebRTC Signaling (Trao đổi kết nối WebRTC)
- `OFFER` - SDP offer từ device hoặc viewer
- `ANSWER` - SDP answer phản hồi
- `ICE_CANDIDATE` - ICE candidate cho NAT traversal

#### Status Updates (Cập nhật trạng thái)
- `DEVICE_STATUS` - Thông báo trạng thái device
- `DEVICES_LIST` - Danh sách devices hiện tại
- `INTERNAL_AUDIO` - Audio nội bộ từ device *(Lưu ý: trong dev.js xử lý như string literal, trong index.js có trong MessageTypes)*
- `ERROR` - Thông báo lỗi

### 4. WebRTC Signaling Flow

Server hoạt động như một signaling server cho WebRTC:

```
1. Device đăng ký với server (DEVICE_REGISTER)
2. Viewer join và nhận danh sách devices (VIEWER_JOIN -> DEVICES_LIST)
3. Viewer yêu cầu stream (REQUEST_STREAM)
4. Device tạo offer (OFFER) -> Server forward đến viewer
5. Viewer tạo answer (ANSWER) -> Server forward đến device
6. Trao đổi ICE candidates qua server (ICE_CANDIDATE)
7. Kết nối WebRTC peer-to-peer được thiết lập
8. Video/audio stream trực tiếp giữa device và viewer
```

### 5. Heartbeat & Watchdog

Server theo dõi trạng thái kết nối của devices:

- Devices gửi `HEARTBEAT` mỗi vài giây
- Server kiểm tra heartbeat mỗi 5 giây
- Nếu không nhận heartbeat trong 20 giây, device bị đánh dấu offline
- Thông báo status update đến tất cả viewers

```javascript
HEARTBEAT_TIMEOUT_MS = 20000;  // 20 giây
HEARTBEAT_CHECK_INTERVAL_MS = 5000;  // Kiểm tra mỗi 5 giây
```

### 6. REST API Endpoints

Server cũng cung cấp các HTTP endpoints:

#### `GET /api/devices`
Lấy danh sách tất cả devices đang connected

**Response:**
```json
[
  {
    "id": "device-123",
    "name": "Samsung S21",
    "type": "android",
    "isConnected": true,
    "isStreaming": true,
    "screenWidth": 1080,
    "screenHeight": 2400
  }
]
```

#### `GET /api/health`
Health check endpoint

**Response:**
```json
{
  "status": "ok",
  "devices": 2,
  "viewers": 1,
  "uptime": 3600.5
}
```

#### `GET *` (Production only - index.js)
Serve frontend React app từ thư mục `dist`

**Lưu ý:** Nếu thư mục `dist` không tồn tại, server sẽ redirect về `http://localhost:3000` (chế độ development)

## Luồng hoạt động

### Khi Device kết nối:
1. Device mở WebSocket connection đến `/ws`
2. Gửi `DEVICE_REGISTER` với deviceId và deviceInfo
3. Server lưu device vào Map và thông báo cho tất cả viewers
4. Device gửi `HEARTBEAT` định kỳ để duy trì kết nối
5. Khi sẵn sàng stream, device gửi `DEVICE_START_STREAM`

### Khi Viewer kết nối:
1. Viewer mở WebSocket connection đến `/ws`
2. Gửi `VIEWER_JOIN`
3. Server trả về `DEVICES_LIST` với tất cả devices đang connected
4. Viewer chọn device và gửi `REQUEST_STREAM`
5. Server forward request đến device
6. WebRTC signaling bắt đầu (OFFER/ANSWER/ICE_CANDIDATE)
7. Stream được thiết lập trực tiếp giữa device và viewer

### Khi ngắt kết nối:
- Server tự động phát hiện WebSocket close/error
- Xóa device/viewer khỏi Map
- Thông báo status update cho các connections còn lại
- Cleanup các active streams

## Dependencies

### Runtime Dependencies:
- `express` - HTTP server framework
- `ws` - WebSocket server
- `cors` - Cross-Origin Resource Sharing
- `uuid` - Generate unique IDs cho viewers

### Dev Dependencies:
- `@types/express`, `@types/ws`, `@types/cors` - TypeScript type definitions (nếu cần)

## Environment Variables

- `PORT` - Port để chạy server (mặc định: 3001)

## Logging

Server có logging chi tiết cho debugging:
- 📱 Device events (register, disconnect)
- 👁️ Viewer events (join, leave)
- 📤/📥 Message forwarding
- 🎬 Stream requests
- 🛑 Stream stops
- 🔄 Reconnections
- ⏱️ Heartbeat timeouts
- 🎵 Internal audio messages
- 🖥️ Screen info updates

## Security Considerations

1. **CORS:** Chỉ cho phép localhost trong development
2. **Message Validation:** Kiểm tra required fields trước khi xử lý
3. **Error Handling:** Try-catch cho tất cả WebSocket operations
4. **Connection Cleanup:** Tự động cleanup khi disconnect
5. **Heartbeat Monitoring:** Phát hiện stale connections

## Khác biệt giữa dev.js và index.js

| Tính năng | dev.js | index.js |
|-----------|--------|----------|
| Serve static files | ❌ Không | ✅ Có (từ dist/) hoặc redirect |
| WebSocket Server | ✅ Có | ✅ Có |
| CORS | localhost:3000, 127.0.0.1:3000 | localhost:3000, 127.0.0.1:3000 |
| Logging chi tiết | ✅ Nhiều hơn | ✅ Có |
| Production ready | ❌ Không | ✅ Có |
| Hot reload | ✅ Dùng với Vite | ❌ Cần rebuild |
| Fallback behavior | N/A | Redirect to localhost:3000 if no dist/ |

## Troubleshooting

### Device không kết nối được:
1. Kiểm tra `WS_URL` trong Android app config
2. Kiểm tra firewall/network
3. Xem server logs để tìm errors
4. Kiểm tra device có gửi `DEVICE_REGISTER` đúng format không

### Viewer không nhận được devices list:
1. Kiểm tra có gửi `VIEWER_JOIN` chưa
2. Kiểm tra devices có `isConnected: true` không
3. Xem network tab trong browser DevTools

### Stream không hoạt động:
1. Kiểm tra WebRTC signaling (OFFER/ANSWER/ICE_CANDIDATE)
2. Kiểm tra network constraints (firewall, NAT)
3. Kiểm tra browser console cho WebRTC errors
4. Kiểm tra permissions (camera, microphone) trên device

### Heartbeat timeout:
1. Đảm bảo device gửi HEARTBEAT định kỳ (< 20s)
2. Kiểm tra connection stability
3. Có thể tăng `HEARTBEAT_TIMEOUT_MS` nếu cần

## Kết luận

Thư mục `server` chứa signaling server quan trọng cho ứng dụng screen sharing. Server này:
- Quản lý kết nối giữa devices và viewers
- Forward WebRTC signaling messages
- Theo dõi trạng thái devices qua heartbeat
- Cung cấp REST API để query devices
- Hỗ trợ cả development và production modes
