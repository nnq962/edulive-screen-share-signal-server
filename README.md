# EduLive Screen Share Signal Server

Một ứng dụng web để hiển thị màn hình được chia sẻ từ các thiết bị Android lên trình duyệt web sử dụng WebRTC và WebSocket.

## 🚀 Tính năng

- **Real-time Screen Sharing**: Chia sẻ màn hình từ điện thoại Android lên web
- **Multi-device Support**: Hiển thị nhiều thiết bị cùng lúc
- **Responsive Design**: Tối ưu cho mọi kích thước màn hình
- **WebRTC Signaling**: Sử dụng WebSocket để trao đổi signaling
- **Device Management**: Quản lý và theo dõi trạng thái thiết bị
- **Fullscreen Mode**: Xem chi tiết từng màn hình

## 📋 Yêu cầu hệ thống

- Node.js >= 16.0.0
- npm hoặc yarn
- Trình duyệt hỗ trợ WebRTC (Chrome, Firefox, Safari)

## 🛠️ Cài đặt

1. **Clone repository**
```bash
git clone <repository-url>
cd edulive-screen-share-signal-server
```

2. **Cài đặt dependencies**
```bash
npm install
```

3. **Chạy server và client**

### Development Mode (2 cổng)
```bash
# Chạy cả server và client cùng lúc
npm run dev:full

# Hoặc chạy riêng lẻ
npm run server  # WebSocket server (port 3001)
npm run dev      # React client (port 3000)
```

### Production Mode (1 cổng)
```bash
# Build và chạy trên cùng một cổng
npm run start

# Truy cập: http://localhost:3000
```

## 🏗️ Kiến trúc

### Backend (Server)
- **Express.js**: HTTP server
- **WebSocket**: Real-time communication
- **CORS**: Cross-origin resource sharing
- **UUID**: Device identification

### Frontend (Client)
- **React 18**: UI framework
- **TypeScript**: Type safety
- **Ant Design**: UI components
- **WebRTC**: Screen sharing
- **WebSocket**: Real-time communication

## 📁 Cấu trúc thư mục

```
edulive-screen-share-signal-server/
├── server/
│   └── index.js              # WebSocket server
├── src/
│   ├── components/
│   ├── hooks/
│   │   └── useWebSocket.ts   # WebSocket hook
│   ├── utils/
│   │   └── webrtc.ts         # WebRTC utilities
│   ├── App.tsx               # Main component
│   └── index.css             # Styles
├── package.json
└── README.md
```

## 🔧 Cấu hình

### Server Configuration
- **Development Port**: 3001 (WebSocket server)
- **Production Port**: 3000 (Unified server)
- **WebSocket URL**: 
  - Development: `ws://localhost:3001/ws`
  - Production: `ws://localhost:3000/ws`
- **API Endpoint**: 
  - Development: `http://localhost:3001/api`
  - Production: `http://localhost:3000/api`

### Client Configuration
- **Development Port**: 3000 (Vite proxy)
- **Production Port**: 3000 (Unified)
- **WebSocket URL**: `ws://localhost:3000/ws`

## 📱 Sử dụng

### 1. Khởi động hệ thống
```bash
npm run dev:full
```

### 2. Truy cập ứng dụng
- Mở trình duyệt và truy cập: `http://localhost:5173`
- Server sẽ chạy trên: `http://localhost:3001`

### 3. Kết nối thiết bị Android
- Sử dụng Room ID để kết nối thiết bị
- Thiết bị sẽ tự động đăng ký và hiển thị trên web

### 4. Quản lý thiết bị
- **Refresh**: Cập nhật danh sách thiết bị
- **Fullscreen**: Xem chi tiết màn hình
- **Connection Status**: Theo dõi trạng thái kết nối

## 🔌 API Endpoints

### WebSocket Messages

#### Device Registration
```json
{
  "type": "DEVICE_REGISTER",
  "deviceId": "device-001",
  "deviceInfo": {
    "id": "device-001",
    "name": "Samsung Galaxy S23",
    "type": "android",
    "isConnected": true,
    "lastSeen": "2024-01-01T00:00:00.000Z",
    "isStreaming": false
  }
}
```

#### Viewer Join
```json
{
  "type": "VIEWER_JOIN",
  "roomId": "room-123"
}
```

#### WebRTC Signaling
```json
{
  "type": "OFFER",
  "deviceId": "device-001",
  "offer": { /* RTCSessionDescription */ }
}
```

### HTTP API

- `GET /api/devices` - Lấy danh sách thiết bị
- `GET /api/rooms/:roomId/devices` - Lấy thiết bị theo room
- `GET /api/health` - Health check

## 🎨 UI Components

### ScreenView Component
- Hiển thị màn hình từ thiết bị
- Device ID tag
- Connection status
- Fullscreen button

### Control Panel
- Room ID management
- Auto-connect toggle
- Server controls

### Header
- Connection status
- Device statistics
- Refresh controls

## 🔒 Bảo mật

- **CORS**: Cấu hình cross-origin
- **Input Validation**: Kiểm tra dữ liệu đầu vào
- **Error Handling**: Xử lý lỗi an toàn
- **Connection Management**: Quản lý kết nối

## 🐛 Troubleshooting

### Lỗi kết nối WebSocket
```bash
# Kiểm tra server có chạy không
curl http://localhost:3001/api/health
```

### Lỗi WebRTC
- Kiểm tra trình duyệt hỗ trợ WebRTC
- Đảm bảo HTTPS cho production
- Kiểm tra firewall/network

### Lỗi dependencies
```bash
# Xóa node_modules và cài lại
rm -rf node_modules package-lock.json
npm install
```

## 🚀 Production Deployment

### 1. Build ứng dụng
```bash
npm run build
```

### 2. Cấu hình server
```bash
# Set environment variables
export PORT=3001
export NODE_ENV=production
```

### 3. Chạy production
```bash
npm run server
```

## 📝 Scripts

- `npm run dev` - Chạy React development server
- `npm run server` - Chạy WebSocket server
- `npm run dev:full` - Chạy cả server và client
- `npm run build` - Build production
- `npm run preview` - Preview production build

## 🤝 Đóng góp

1. Fork repository
2. Tạo feature branch
3. Commit changes
4. Push to branch
5. Tạo Pull Request

## 📄 License

MIT License - xem file LICENSE để biết thêm chi tiết.

## 📞 Hỗ trợ

Nếu gặp vấn đề, vui lòng tạo issue trên GitHub repository.