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
const viewers = new Map(); // viewerId -> { connection, supportsBinary, capabilities }

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
  
  // Internal Audio
  INTERNAL_AUDIO: 'INTERNAL_AUDIO',
  DEVICE_SCREEN_INFO: 'DEVICE_SCREEN_INFO',
  
  // Control commands
  CONTROL_COMMAND: 'CONTROL_COMMAND',
  REQUEST_STREAM: 'REQUEST_STREAM',
  STOP_STREAM: 'STOP_STREAM',
  
  // Error handling
  ERROR: 'ERROR'
};

wss.on('connection', (ws, req) => {
  console.log('New connection established');
  
  ws.on('message', (data) => {
    try {
      console.log('📥 [SERVER] Received message:', {
        isBuffer: Buffer.isBuffer(data),
        length: data.length,
        firstBytes: Buffer.isBuffer(data) ? Array.from(data.slice(0, Math.min(8, data.length))).map(b => '0x' + b.toString(16).padStart(2, '0')).join(' ') : 'N/A'
      });
      
      // Check if message is binary or JSON
      if (Buffer.isBuffer(data) && data.length >= 12) {
        // Try to parse as binary protocol
        const version = data[0];
        const messageType = data[1];
        
        console.log('🔍 [SERVER] Binary check:', {
          version: '0x' + version.toString(16).padStart(2, '0'),
          messageType: '0x' + messageType.toString(16).padStart(2, '0'),
          isBinaryAudio: version === 0x01 && messageType === 0x01
        });
        
        if (version === 0x01 && messageType === 0x01) {
          // Binary INTERNAL_AUDIO message
          console.log('🎵 [SERVER] Processing as binary INTERNAL_AUDIO');
          handleBinaryInternalAudio(ws, data);
          return;
        }
      }
      
      // Default: parse as JSON
      console.log('📝 [SERVER] Processing as JSON message');
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
      
    case MessageTypes.DEVICE_SCREEN_INFO:
      handleDeviceScreenInfo(ws, message);
      break;
      
    case MessageTypes.CONTROL_COMMAND:
      handleControlCommand(ws, message);
      break;
      
    case MessageTypes.REQUEST_STREAM:
      handleRequestStream(ws, message);
      break;
      
    case MessageTypes.STOP_STREAM:
      handleStopStream(ws, message);
      break;
      
    default:
      sendError(ws, 'Unknown message type');
  }
}

function handleDeviceRegister(ws, message) {
  const { deviceId, deviceInfo } = message;
  
  console.log('📱 [DEVICE] Device register:', {
    deviceId,
    deviceInfo,
    existingDevice: devices.has(deviceId)
  });
  
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
  
  console.log('✅ [DEVICE] Device registered successfully:', deviceId);
  console.log('📋 [DEVICE] All devices:', Array.from(devices.keys()));
  
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
  const { capabilities } = message;
  
  console.log('👀 [VIEWER] Viewer join:', {
    viewerId,
    capabilities,
    hasBinaryAudioCapability: capabilities?.supportsBinaryAudio
  });
  
  // Track binary audio support capability
  const supportsBinary = capabilities?.supportsBinaryAudio || false;
  
  viewers.set(viewerId, {
    connection: ws,
    supportsBinary: supportsBinary,
    capabilities: capabilities || {}
  });
  
  // Send current devices to new viewer
  const deviceList = Array.from(devices.values()).map(d => d.deviceInfo);
  ws.send(JSON.stringify({
    type: MessageTypes.DEVICES_LIST,
    devices: deviceList
  }));
  
  console.log(`📤 Sending DEVICES_LIST to viewer ${viewerId} - Binary audio: ${supportsBinary ? 'SUPPORTED' : 'NOT SUPPORTED'}`);
  console.log('👥 [VIEWERS] Current viewers:', Array.from(viewers.values()).map(v => ({ supportsBinary: v.supportsBinary })));
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

/**
 * Handle binary INTERNAL_AUDIO messages
 * Binary protocol: Version(1) | MessageType(1) | SampleRate(2) | Channels(1) | DeviceIdLen(1) | PayloadLen(4) | Reserved(2) | DeviceId(variable) | AudioData(variable)
 */
function handleBinaryInternalAudio(ws, buffer) {
  try {
    if (buffer.length < 12) {
      console.error('❌ [BINARY] Invalid binary message length:', buffer.length);
      return;
    }
    
    let offset = 0;
    
    // Parse header
    const version = buffer[offset++];
    const messageType = buffer[offset++];
    const sampleRate = buffer.readUInt16LE(offset); offset += 2;
    const channels = buffer[offset++];
    const deviceIdLen = buffer[offset++];
    const payloadLen = buffer.readUInt32LE(offset); offset += 4;
    
    // Skip reserved bytes
    offset += 2;
    
    if (buffer.length < 12 + deviceIdLen + payloadLen) {
      console.error('❌ [BINARY] Buffer too short for declared lengths');
      return;
    }
    
    // Parse device ID
    const deviceId = buffer.subarray(offset, offset + deviceIdLen).toString('utf8');
    offset += deviceIdLen;
    
    // Parse audio data
    const audioData = buffer.subarray(offset, offset + payloadLen);
    
    const device = devices.get(deviceId);
    if (!device) {
      console.warn('❌ [BINARY] Audio from unknown device:', deviceId);
      return;
    }
    
    // Split viewers by binary capability - optimized for binary-only mode
    let binaryViewers = 0;
    let unsupportedViewers = 0;
    
    viewers.forEach(viewer => {
      try {
        if (viewer.supportsBinary) {
          // Send binary data directly to binary-capable viewers
          viewer.connection.send(buffer);
          binaryViewers++;
        } else {
          // Drop audio for non-binary viewers to maintain performance
          unsupportedViewers++;
          console.warn('⚠️ [BINARY] Dropping audio for non-binary viewer (performance mode)');
        }
      } catch (error) {
        console.error('❌ [BINARY] Error sending audio to viewer:', error);
      }
    });
    
    if (unsupportedViewers > 0) {
      console.warn(`⚠️ [BINARY] ${unsupportedViewers} viewers don't support binary - audio dropped for performance`);
    }
    
    console.log(`✅ [BINARY] High-performance broadcast: ${binaryViewers} binary viewers (${audioData.length} bytes)`);
    
    
  } catch (error) {
    console.error('❌ [BINARY] Error processing binary audio:', error);
  }
}

function handleDeviceScreenInfo(ws, message) {
  const { deviceId, data } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    console.warn('Screen info from unknown device:', deviceId);
    return;
  }
  
  // Update device screen info
  if (data && data.width && data.height) {
    device.deviceInfo.screenWidth = data.width;
    device.deviceInfo.screenHeight = data.height;
  }
  
  // Forward to viewers
  broadcastToViewers({
    type: MessageTypes.DEVICE_SCREEN_INFO,
    deviceId: deviceId,
    data: data
  });
}

function handleControlCommand(ws, message) {
  const { deviceId, data } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    sendError(ws, 'Target device not found');
    return;
  }
  
  // Forward control command to device
  device.connection.send(JSON.stringify({
    type: MessageTypes.CONTROL_COMMAND,
    data: data
  }));
}

function handleRequestStream(ws, message) {
  const { deviceId } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    sendError(ws, 'Device not found');
    return;
  }
  
  // Generate viewer ID for this request
  const viewerId = uuidv4();
  
  // Notify device to start streaming
  device.connection.send(JSON.stringify({
    type: MessageTypes.REQUEST_STREAM,
    viewerId: viewerId
  }));
  
  console.log(`Viewer requested stream from device ${deviceId}`);
}

function handleStopStream(ws, message) {
  const { deviceId } = message;
  const device = devices.get(deviceId);
  
  if (!device) {
    return;
  }
  
  // Notify device to stop streaming
  device.connection.send(JSON.stringify({
    type: MessageTypes.STOP_STREAM
  }));
  
  console.log(`Viewer stopped stream from device ${deviceId}`);
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
