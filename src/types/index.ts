// Device information interface
export interface DeviceInfo {
  id: string;
  name: string;
  type: string;
  isConnected: boolean;
  lastSeen: Date;
  isStreaming: boolean;
  roomId?: string;
  screenWidth?: number;
  screenHeight?: number;
}

// WebSocket message interface
export interface WebSocketMessage {
  type: string;
  deviceId?: string;
  roomId?: string;
  data?: any;
  [key: string]: any;
}

// Message types for WebSocket communication
export const MessageTypes = {
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
  
  // Error handling
  ERROR: 'ERROR'
} as const;
