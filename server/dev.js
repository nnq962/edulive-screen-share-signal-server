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

// Store connected devices and viewers
const devices = new Map(); // deviceId -> { connection, deviceInfo, stream }
const viewers = new Map(); // viewerId -> { connection }

// WebSocket Server
const wss = new WebSocketServer({ 
  server,
  path: '/ws'
});

function logConnectedDevices(context) {
  const connectedDevices = Array.from(devices.values())
    .filter(device => device.deviceInfo.isConnected);
  if (connectedDevices.length === 0) {
    console.log(`📊 ${context}: no devices connected`);
    return;
  }

  const deviceSummary = connectedDevices
    .map(device => `${device.deviceInfo.name} (${device.deviceInfo.id})`)
    .join(', ');

  console.log(`📊 ${context}: ${connectedDevices.length} connected -> ${deviceSummary}`);
}

// Message Types
const MessageTypes = {
  // Device messages
  DEVICE_REGISTER: 'DEVICE_REGISTER',
  DEVICE_START_STREAM: 'DEVICE_START_STREAM',
  DEVICE_STOP_STREAM: 'DEVICE_STOP_STREAM',
  
  // Viewer messages
  VIEWER_JOIN: 'VIEWER_JOIN',
  VIEWER_LEAVE: 'VIEWER_LEAVE',
  REQUEST_STREAM: 'REQUEST_STREAM',
  STOP_STREAM: 'STOP_STREAM',
  CONTROL_COMMAND: 'CONTROL_COMMAND',
  
  // WebRTC signaling
  OFFER: 'OFFER',
  ANSWER: 'ANSWER',
  ICE_CANDIDATE: 'ICE_CANDIDATE',
  
  // Status updates
  DEVICE_STATUS: 'DEVICE_STATUS',
  DEVICES_LIST: 'DEVICES_LIST',
  HEARTBEAT: 'HEARTBEAT',
  
  // Admin actions removed (server always welcome)
  
  // Error handling
  ERROR: 'ERROR'
};

const HEARTBEAT_TIMEOUT_MS = 20000; // 20 seconds grace period
const HEARTBEAT_CHECK_INTERVAL_MS = 5000;

wss.on('connection', (ws, req) => {
  console.log('New connection established');
  
  ws.on('message', (data) => {
    try {
      const message = JSON.parse(data.toString());
      console.log('📨 Message received:', message.type);
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
      
    case MessageTypes.REQUEST_STREAM:
      handleRequestStream(ws, message);
      break;

    case MessageTypes.STOP_STREAM:
      handleStopStream(ws, message);
      break;
      
    case MessageTypes.CONTROL_COMMAND:
      handleControlCommand(ws, message);
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
    
    // Admin disconnect disabled
      
      
    default:
      sendError(ws, 'Unknown message type');
  }
}

function handleDeviceRegister(ws, message) {
  const { deviceId, deviceInfo } = message;
  
  if (devices.has(deviceId)) {
    // Replace stale connection while keeping device metadata stable
    const existing = devices.get(deviceId);
    if (existing?.connection && existing.connection !== ws) {
      try {
        existing.connection.close(4001, 'Replaced by new registration');
      } catch (error) {
        console.error(`Error closing previous socket for ${deviceId}:`, error.message);
      }
    }
  }

  const previousInfo = devices.get(deviceId)?.deviceInfo;

  devices.set(deviceId, {
    connection: ws,
    deviceInfo: {
      id: deviceId,
      name: deviceInfo.name || previousInfo?.name || `Device ${deviceId}`,
      type: deviceInfo.type || previousInfo?.type || 'android',
      isConnected: true,
      lastSeen: Date.now(),
      isStreaming: previousInfo?.isStreaming ?? false
    },
    stream: null
  });
  
  // Notify all viewers about new device
  broadcastToViewers({
    type: MessageTypes.DEVICE_STATUS,
    device: devices.get(deviceId).deviceInfo
  });
  
  console.log(`📱 Device ${deviceId} registered`);
  logConnectedDevices('Connected devices after register');
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
  logConnectedDevices('Connected devices after start stream');
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
  logConnectedDevices('Connected devices after stop stream');
  removeDeviceFromViewers(deviceId, 'Device stopped streaming');
}

function handleViewerJoin(ws, message) {
  const viewerId = uuidv4();
  
  viewers.set(viewerId, {
    connection: ws,
    activeDevices: new Set()
  });
  
  // Send current devices to new viewer (only connected devices)
  const deviceList = Array.from(devices.values())
    .filter(d => d.deviceInfo.isConnected)
    .map(d => d.deviceInfo);
  
  console.log(`📤 Sending DEVICES_LIST to viewer ${viewerId}:`, deviceList.length, 'devices');
  console.log('📱 Device details:', deviceList);
  
  ws.send(JSON.stringify({
    type: MessageTypes.DEVICES_LIST,
    devices: deviceList
  }));
  
  console.log(`👁️ Viewer ${viewerId} joined`);
  console.log(`📊 Current connected devices: ${deviceList.length}, viewers: ${viewers.size}`);
  logConnectedDevices(`Devices available to viewer ${viewerId}`);
}

function handleViewerLeave(ws, message) {
  // Remove viewer from map
  for (const [viewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      notifyDevicesViewerLeft(viewerId, viewer);
      viewers.delete(viewerId);
      console.log(`Viewer ${viewerId} left`);
      break;
    }
  }
}

function handleRequestStream(ws, message) {
  const { deviceId } = message;
  if (!deviceId) {
    sendError(ws, 'Missing deviceId in REQUEST_STREAM');
    return;
  }

  const viewerId = getViewerIdByConnection(ws);
  if (!viewerId) {
    sendError(ws, 'Viewer not registered');
    return;
  }

  const device = devices.get(deviceId);
  if (!device || !device.deviceInfo.isConnected) {
    sendError(ws, 'Requested device not available');
    return;
  }

  const viewer = viewers.get(viewerId);
  if (!viewer) {
    sendError(ws, 'Viewer session not found');
    return;
  }

  if (viewer.activeDevices.has(deviceId)) {
    console.log(`ℹ️ Viewer ${viewerId} already subscribed to device ${deviceId}`);
    return;
  }

  viewer.activeDevices.add(deviceId);

  try {
    device.connection.send(JSON.stringify({
      type: MessageTypes.REQUEST_STREAM,
      viewerId,
      deviceId
    }));
    console.log(`🎬 Stream requested by viewer ${viewerId} for device ${deviceId}`);
  } catch (error) {
    console.error(`❌ Failed to forward stream request to device ${deviceId}:`, error.message);
  }
}

function handleStopStream(ws, message) {
  const { deviceId } = message;
  if (!deviceId) {
    sendError(ws, 'Missing deviceId in STOP_STREAM');
    return;
  }

  const viewerId = getViewerIdByConnection(ws);
  if (!viewerId) {
    sendError(ws, 'Viewer not registered');
    return;
  }

  const viewer = viewers.get(viewerId);
  if (viewer?.activeDevices.has(deviceId)) {
    viewer.activeDevices.delete(deviceId);
  }

  const device = devices.get(deviceId);
  if (!device) {
    return;
  }

  try {
    device.connection.send(JSON.stringify({
      type: MessageTypes.STOP_STREAM,
      viewerId,
      deviceId
    }));
    console.log(`🛑 Stream stop requested by viewer ${viewerId} for device ${deviceId}`);
  } catch (error) {
    console.error(`❌ Failed to forward stop request to device ${deviceId}:`, error.message);
  }
}

function handleControlCommand(ws, message) {
  const { deviceId, data } = message;
  if (!deviceId || !data) {
    sendError(ws, 'Missing deviceId or command payload in CONTROL_COMMAND');
    return;
  }

  const viewerId = getViewerIdByConnection(ws);
  if (!viewerId) {
    sendError(ws, 'Viewer not registered');
    return;
  }

  const viewer = viewers.get(viewerId);
  if (!viewer) {
    sendError(ws, 'Viewer session not found');
    return;
  }

  if (!viewer.activeDevices.has(deviceId)) {
    sendError(ws, 'Viewer not subscribed to device');
    return;
  }

  const device = devices.get(deviceId);
  if (!device) {
    sendError(ws, 'Target device not found');
    return;
  }

  try {
    device.connection.send(JSON.stringify({
      type: MessageTypes.CONTROL_COMMAND,
      data,
      viewerId
    }));
    console.log(`🎮 Control command forwarded from viewer ${viewerId} to device ${deviceId}`);
  } catch (error) {
    console.error(`❌ Failed to forward control command to device ${deviceId}:`, error.message);
  }
}

function getViewerIdByConnection(ws) {
  for (const [viewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      return viewerId;
    }
  }
  return null;
}

function notifyDevicesViewerLeft(viewerId, viewer) {
  if (!viewer || !viewer.activeDevices || viewer.activeDevices.size === 0) {
    return;
  }

  viewer.activeDevices.forEach(deviceId => {
    const device = devices.get(deviceId);
    if (!device) {
      return;
    }
    try {
      device.connection.send(JSON.stringify({
        type: MessageTypes.STOP_STREAM,
        viewerId,
        deviceId
      }));
      console.log(`🛑 Viewer ${viewerId} disconnected from device ${deviceId}`);
    } catch (error) {
      console.error(`❌ Failed to notify device ${deviceId} about viewer ${viewerId} disconnect:`, error.message);
    }
  });

  viewer.activeDevices.clear();
}

function removeDeviceFromViewers(deviceId, reason) {
  viewers.forEach((viewer, viewerId) => {
    if (viewer?.activeDevices?.delete(deviceId)) {
      console.log(`ℹ️ Removed device ${deviceId} from viewer ${viewerId} (${reason})`);
    }
  });
}

function handleOffer(ws, message) {
  const { deviceId, targetDeviceId, offer } = message;
  console.log(`📤 OFFER received from device ${deviceId} to ${targetDeviceId}`);
  
  if (targetDeviceId === 'web-viewer') {
    // Forward offer to all viewers (web clients)
    const offerMessage = JSON.stringify({
      type: MessageTypes.OFFER,
      deviceId: deviceId,
      offer: offer
    });
    
    let forwardedCount = 0;
    for (const [viewerId, viewer] of viewers.entries()) {
      try {
        viewer.connection.send(offerMessage);
        forwardedCount++;
        console.log(`📤 Offer forwarded to viewer ${viewerId}`);
      } catch (error) {
        console.error(`❌ Failed to forward offer to viewer ${viewerId}:`, error.message);
      }
    }
    
    console.log(`📤 Offer forwarded to ${forwardedCount} viewers`);
  } else if (viewers.has(targetDeviceId)) {
    const viewer = viewers.get(targetDeviceId);
    try {
      viewer.connection.send(JSON.stringify({
        type: MessageTypes.OFFER,
        deviceId,
        offer
      }));
      console.log(`📤 Offer forwarded to viewer ${targetDeviceId}`);
    } catch (error) {
      console.error(`❌ Failed to forward offer to viewer ${targetDeviceId}:`, error.message);
    }
  } else {
    // Forward to specific device (legacy support)
    const device = devices.get(targetDeviceId);
    if (!device) {
      sendError(ws, 'Target device not found');
      return;
    }
    
    device.connection.send(JSON.stringify({
      type: MessageTypes.OFFER,
      offer: offer,
      fromViewer: true
    }));
    
    console.log(`📤 Offer forwarded to device ${targetDeviceId}`);
  }
}

function handleAnswer(ws, message) {
  const { deviceId, answer } = message;

  let viewerId = null;
  for (const [currentViewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      viewerId = currentViewerId;
      break;
    }
  }

  console.log(`📤 ANSWER received from viewer ${viewerId || 'unknown'} to device ${deviceId}`);
  
  // Find the device to send answer to
  const device = devices.get(deviceId);
  if (!device) {
    console.error(`❌ Device ${deviceId} not found for answer`);
    return;
  }
  
  try {
    // Forward answer to Android device
    device.connection.send(JSON.stringify({
      type: MessageTypes.ANSWER,
      answer: answer,
      viewerId
    }));
    console.log(`📤 Answer forwarded to device ${deviceId}`);
  } catch (error) {
    console.error(`❌ Failed to forward answer to device ${deviceId}:`, error.message);
  }
}

function handleIceCandidate(ws, message) {
  const { deviceId, targetDeviceId, candidate } = message;
  console.log(`📤 ICE_CANDIDATE received from ${deviceId || 'unknown'} to ${targetDeviceId || 'unspecified'}`);
  
  // Check if message is from device
  for (const [currentDeviceId, device] of devices.entries()) {
    if (device.connection === ws) {
      // Forward ICE candidate from device to viewers
      const iceMessage = JSON.stringify({
        type: MessageTypes.ICE_CANDIDATE,
        deviceId: currentDeviceId,
        candidate: candidate
      });

      if (targetDeviceId && viewers.has(targetDeviceId)) {
        const viewer = viewers.get(targetDeviceId);
        try {
          viewer.connection.send(iceMessage);
          console.log(`📤 ICE candidate forwarded to viewer ${targetDeviceId}`);
        } catch (error) {
          console.error(`❌ Failed to forward ICE candidate to viewer ${targetDeviceId}:`, error.message);
        }
        return;
      }

      let forwardedCount = 0;
      for (const [viewerId, viewer] of viewers.entries()) {
        try {
          viewer.connection.send(iceMessage);
          forwardedCount++;
        } catch (error) {
          console.error(`❌ Failed to forward ICE candidate to viewer ${viewerId}:`, error.message);
        }
      }

      console.log(`📤 ICE candidate forwarded to ${forwardedCount} viewers`);
      return;
    }
  }
  
  // If from viewer, forward to specific device
  const device = devices.get(deviceId);
  if (device) {
    let viewerId = null;
    for (const [currentViewerId, viewer] of viewers.entries()) {
      if (viewer.connection === ws) {
        viewerId = currentViewerId;
        break;
      }
    }
    try {
      device.connection.send(JSON.stringify({
        type: MessageTypes.ICE_CANDIDATE,
        candidate: candidate,
        viewerId
      }));
      console.log(`📤 ICE candidate forwarded to device ${deviceId} from viewer ${viewerId || 'unknown'}`);
    } catch (error) {
      console.error(`❌ Failed to forward ICE candidate to device ${deviceId}:`, error.message);
    }
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
      console.log(`📱 Device ${deviceId} disconnected`);
      logConnectedDevices('Connected devices after disconnect');
      removeDeviceFromViewers(deviceId, 'Device disconnected');
      break;
    }
  }
  
  // Remove viewer
  for (const [viewerId, viewer] of viewers.entries()) {
    if (viewer.connection === ws) {
      notifyDevicesViewerLeft(viewerId, viewer);
      viewers.delete(viewerId);
      console.log(`👁️ Viewer ${viewerId} disconnected`);
      break;
    }
  }
}

function handleHeartbeat(ws, message) {
  const { deviceId } = message;
  if (!deviceId) {
    sendError(ws, 'Missing deviceId in HEARTBEAT');
    return;
  }

  const device = devices.get(deviceId);
  if (!device) {
    sendError(ws, 'Device not registered for HEARTBEAT');
    return;
  }

  device.deviceInfo.lastSeen = Date.now();
  if (!device.deviceInfo.isConnected) {
    device.deviceInfo.isConnected = true;
    broadcastToViewers({
      type: MessageTypes.DEVICE_STATUS,
      device: device.deviceInfo
    });
    console.log(`🔄 Device ${deviceId} reconnected via heartbeat`);
    logConnectedDevices('Connected devices after heartbeat reconnect');
  }
}

// Admin disconnect handler removed

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

setInterval(() => {
  const now = Date.now();
  devices.forEach((device, deviceId) => {
    const lastSeen = device.deviceInfo.lastSeen || 0;
    if (device.deviceInfo.isConnected && now - lastSeen > HEARTBEAT_TIMEOUT_MS) {
      device.deviceInfo.isConnected = false;
      device.deviceInfo.isStreaming = false;
      broadcastToViewers({
        type: MessageTypes.DEVICE_STATUS,
        device: device.deviceInfo
      });
      console.log(`⏱️ Device ${deviceId} marked offline (no heartbeat in ${HEARTBEAT_TIMEOUT_MS}ms)`);
      logConnectedDevices('Connected devices after heartbeat timeout');
    }
  });
}, HEARTBEAT_CHECK_INTERVAL_MS);

// REST API endpoints
app.get('/api/devices', (req, res) => {
  const deviceList = Array.from(devices.values())
    .filter(d => d.deviceInfo.isConnected)
    .map(d => d.deviceInfo);
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

// Clear all devices on server start
devices.clear();
viewers.clear();
console.log('🧹 Cleared all previous connections');

const PORT = process.env.PORT || 3001;
server.listen(PORT, () => {
  console.log(`🚀 Development WebSocket Server running on port ${PORT}`);
  console.log(`📱 WebSocket endpoint: ws://localhost:${PORT}/ws`);
  console.log(`🌐 HTTP API: http://localhost:${PORT}/api`);
  console.log(`🌐 Web App: http://localhost:3000 (Vite dev server)`);
});
