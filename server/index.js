import express from 'express';
import { WebSocketServer } from 'ws';
import { createServer } from 'http';
import cors from 'cors';
import { v4 as uuidv4 } from 'uuid';

const app = express();
const server = createServer(app);

// Middleware
app.use(cors({
  origin: ['http://localhost:3000', 'http://127.0.0.1:3000'],
  credentials: true
}));
app.use(express.json());

// Serve static files from Vite build (only if dist folder exists)
import { existsSync } from 'fs';
if (existsSync('dist')) {
  app.use(express.static('dist'));
}

// Store connected devices and viewers
const devices = new Map(); // deviceId -> { connection, deviceInfo, stream }
const viewers = new Map(); // viewerId -> { connection }

// WebSocket Server
const wss = new WebSocketServer({ 
  server,
  path: '/ws'
});

// Message Types
const MessageTypes = {
  // Device messages
  DEVICE_REGISTER: 'DEVICE_REGISTER',
  DEVICE_START_STREAM: 'DEVICE_START_STREAM',
  DEVICE_STOP_STREAM: 'DEVICE_STOP_STREAM',
  
  // Viewer messages
  VIEWER_JOIN: 'VIEWER_JOIN',
  VIEWER_LEAVE: 'VIEWER_LEAVE',
  
  // WebRTC signaling
  OFFER: 'OFFER',
  ANSWER: 'ANSWER',
  ICE_CANDIDATE: 'ICE_CANDIDATE',
  
  // Status updates
  DEVICE_STATUS: 'DEVICE_STATUS',
  DEVICES_LIST: 'DEVICES_LIST',
  HEARTBEAT: 'HEARTBEAT',
  
  // Error handling
  ERROR: 'ERROR'
};

wss.on('connection', (ws, req) => {
  console.log('New connection established');
  
  ws.on('message', (data) => {
    try {
      const message = JSON.parse(data.toString());
      handleMessage(ws, message);
    } catch (error) {
      console.error('Error parsing message:', error);
      sendError(ws, 'Invalid message format');
    }
  });
  
  ws.on('close', () => {
    console.log('Connection closed');
    handleDisconnection(ws);
  });
  
  ws.on('error', (error) => {
    console.error('WebSocket error:', error);
    handleDisconnection(ws);
  });
});

function handleMessage(ws, message) {
  const { type, deviceId, data } = message;
  
  switch (type) {
    case MessageTypes.DEVICE_REGISTER:
      handleDeviceRegister(ws, message);
      break;
      
    case MessageTypes.DEVICE_START_STREAM:
      handleDeviceStartStream(ws, message);
      break;
      
    case MessageTypes.DEVICE_STOP_STREAM:
      handleDeviceStopStream(ws, message);
      break;
    
    case MessageTypes.HEARTBEAT:
      handleHeartbeat(ws, message);
      break;
      
    case MessageTypes.VIEWER_JOIN:
      handleViewerJoin(ws, message);
      break;
      
    case MessageTypes.VIEWER_LEAVE:
      handleViewerLeave(ws, message);
      break;
      
    case MessageTypes.OFFER:
      handleOffer(ws, message);
      break;
      
    case MessageTypes.ANSWER:
      handleAnswer(ws, message);
      break;
      
    case MessageTypes.ICE_CANDIDATE:
      handleIceCandidate(ws, message);
      break;
      
    default:
      sendError(ws, 'Unknown message type');
  }
}

function handleDeviceRegister(ws, message) {
  const { deviceId, deviceInfo } = message;
  
  // Allow re-register: close old socket if exists, then replace with new
  if (devices.has(deviceId)) {
    try {
      const old = devices.get(deviceId);
      if (old && old.connection && old.connection.readyState === 1) {
        try { old.connection.close(4001, 'Duplicate register, replacing'); } catch {}
      }
    } catch {}
  }
  devices.set(deviceId, {
    connection: ws,
    deviceInfo: {
      id: deviceId,
      name: deviceInfo.name || `Device ${deviceId}`,
      type: deviceInfo.type || 'android',
      isConnected: true,
      lastSeen: Date.now(),
      isStreaming: false
    },
    stream: null
  });
  
  // Notify all viewers about new device
  broadcastToViewers({
    type: MessageTypes.DEVICE_STATUS,
    device: devices.get(deviceId).deviceInfo
  });
  
  console.log(`Device ${deviceId} registered`);
}

function handleDeviceStartStream(ws, message) {
  const { deviceId } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    sendError(ws, 'Device not found');
    return;
  }
  
  device.deviceInfo.isStreaming = true;
  device.deviceInfo.lastSeen = Date.now();
  
  // Notify all viewers about streaming start
  broadcastToViewers({
    type: MessageTypes.DEVICE_STATUS,
    device: device.deviceInfo
  });
  
  console.log(`Device ${deviceId} started streaming`);
}

function handleDeviceStopStream(ws, message) {
  const { deviceId } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    sendError(ws, 'Device not found');
    return;
  }
  
  device.deviceInfo.isStreaming = false;
  device.deviceInfo.lastSeen = Date.now();
  
  // Notify all viewers about streaming stop
  broadcastToViewers({
    type: MessageTypes.DEVICE_STATUS,
    device: device.deviceInfo
  });
  
  console.log(`Device ${deviceId} stopped streaming`);
}

function handleViewerJoin(ws, message) {
  const viewerId = uuidv4();
  
  viewers.set(viewerId, {
    connection: ws
  });
  
  // Send current devices to new viewer
  const deviceList = Array.from(devices.values()).map(d => d.deviceInfo);
  ws.send(JSON.stringify({
    type: MessageTypes.DEVICES_LIST,
    devices: deviceList
  }));
  
  console.log(`Viewer ${viewerId} joined`);
}

function handleViewerLeave(ws, message) {
  // Remove viewer from map
  for (const [viewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      viewers.delete(viewerId);
      console.log(`Viewer ${viewerId} left`);
      break;
    }
  }
}

function handleOffer(ws, message) {
  const { targetDeviceId, offer } = message;
  const device = devices.get(targetDeviceId);
  
  if (!device) {
    sendError(ws, 'Target device not found');
    return;
  }
  
  // Forward offer to device
  device.connection.send(JSON.stringify({
    type: MessageTypes.OFFER,
    offer: offer,
    fromViewer: true
  }));
}

function handleAnswer(ws, message) {
  const { targetViewerId, answer } = message;
  // Find viewer by connection
  for (const viewer of viewers.values()) {
    if (viewer.connection === ws) {
      // Forward answer to specific viewer
      viewer.connection.send(JSON.stringify({
        type: MessageTypes.ANSWER,
        answer: answer,
        fromDevice: true
      }));
      break;
    }
  }
}

function handleIceCandidate(ws, message) {
  const { targetDeviceId, candidate } = message;
  
  // Check if message is from device
  for (const [deviceId, device] of devices.entries()) {
    if (device.connection === ws) {
      // Forward ICE candidate to all viewers
      broadcastToViewers({
        type: MessageTypes.ICE_CANDIDATE,
        candidate: candidate,
        fromDevice: deviceId
      });
      return;
    }
  }
  
  // If from viewer, forward to specific device
  const device = devices.get(targetDeviceId);
  if (device) {
    device.connection.send(JSON.stringify({
      type: MessageTypes.ICE_CANDIDATE,
      candidate: candidate,
      fromViewer: true
    }));
  }
}

function handleDisconnection(ws) {
  // Remove device
  for (const [deviceId, device] of devices.entries()) {
    if (device.connection === ws) {
      device.deviceInfo.isConnected = false;
      device.deviceInfo.isStreaming = false;
      
      // Notify viewers about device disconnection
      broadcastToViewers({
        type: MessageTypes.DEVICE_STATUS,
        device: device.deviceInfo
      });
      
      devices.delete(deviceId);
      console.log(`Device ${deviceId} disconnected`);
      break;
    }
  }
  
  // Remove viewer
  for (const [viewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      viewers.delete(viewerId);
      console.log(`Viewer ${viewerId} disconnected`);
      break;
    }
  }
}

function handleHeartbeat(ws, message) {
  const { deviceId } = message;
  const device = devices.get(deviceId);
  if (!device) return;
  device.deviceInfo.lastSeen = Date.now();
  if (!device.deviceInfo.isConnected) {
    device.deviceInfo.isConnected = true;
    broadcastToViewers({
      type: MessageTypes.DEVICE_STATUS,
      device: device.deviceInfo
    });
  }
}

// Watchdog: mark devices offline if no heartbeat in grace period
const HEARTBEAT_TIMEOUT_MS = 20000; // 20s
setInterval(() => {
  const now = Date.now();
  for (const [deviceId, device] of devices.entries()) {
    if (device.deviceInfo.isConnected && now - (device.deviceInfo.lastSeen || 0) > HEARTBEAT_TIMEOUT_MS) {
      device.deviceInfo.isConnected = false;
      device.deviceInfo.isStreaming = false;
      broadcastToViewers({
        type: MessageTypes.DEVICE_STATUS,
        device: device.deviceInfo
      });
      console.log(`⏱️ Device ${deviceId} marked offline by watchdog`);
    }
  }
}, 5000);

function broadcastToViewers(message) {
  viewers.forEach(viewer => {
    try {
      viewer.connection.send(JSON.stringify(message));
    } catch (error) {
      console.error('Error broadcasting to viewer:', error);
    }
  });
}

function sendError(ws, message) {
  try {
    ws.send(JSON.stringify({
      type: MessageTypes.ERROR,
      message: message
    }));
  } catch (error) {
    console.error('Error sending error message:', error);
  }
}

// REST API endpoints
app.get('/api/devices', (req, res) => {
  const deviceList = Array.from(devices.values()).map(d => d.deviceInfo);
  res.json(deviceList);
});

// Removed room-specific endpoint - all devices are global now

// Health check
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    devices: devices.size,
    viewers: viewers.size,
    uptime: process.uptime()
  });
});

// Serve React app for all non-API routes (only if dist folder exists)
if (existsSync('dist')) {
  app.get('*', (req, res) => {
    res.sendFile('dist/index.html', { root: '.' });
  });
} else {
  // Development mode - redirect to Vite dev server
  app.get('*', (req, res) => {
    res.redirect('http://localhost:3000');
  });
}

// Clear all devices on server start
devices.clear();
viewers.clear();
console.log('🧹 Cleared all previous connections');

const PORT = process.env.PORT || 3001;
server.listen(PORT, () => {
  console.log(`🚀 Screen Share Signal Server running on port ${PORT}`);
  console.log(`📱 WebSocket endpoint: ws://localhost:${PORT}/ws`);
  console.log(`🌐 HTTP API: http://localhost:${PORT}/api`);
  console.log(`🌐 Web App: http://localhost:3000`);
});
