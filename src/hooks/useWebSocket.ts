import { useState, useEffect, useRef, useCallback } from 'react';
import type { WebSocketMessage, DeviceInfo } from '../types';
import { MessageTypes } from '../types';

interface UseWebSocketOptions {
  url: string;
  deviceId?: string;
  onMessage?: (message: WebSocketMessage) => void;
  onError?: (error: Event) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

interface UseWebSocketReturn {
  sendMessage: (message: WebSocketMessage) => void;
  isConnected: boolean;
  devices: DeviceInfo[];
  error: string | null;
  reconnect: () => void;
}

export const useWebSocket = (options: UseWebSocketOptions): UseWebSocketReturn => {
  const { url, deviceId, onMessage, onError, onOpen, onClose } = options;
  
  const [isConnected, setIsConnected] = useState(false);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectAttempts = useRef(0);
  const maxReconnectAttempts = 5;
  const isConnectingRef = useRef(false);

  // Persist callbacks to avoid re-creating connections on every render
  const callbacksRef = useRef({ onMessage, onError, onOpen, onClose });
  useEffect(() => {
    callbacksRef.current = { onMessage, onError, onOpen, onClose };
  }, [onMessage, onError, onOpen, onClose]);

  const connect = useCallback(() => {
    try {
      // Prevent multiple connections or reuse existing OPEN/CONNECTING connection
      const current = wsRef.current;
      if (isConnectingRef.current) {
        console.log('Already connecting, skipping...');
        return;
      }
      if (current && (current.readyState === WebSocket.OPEN || current.readyState === WebSocket.CONNECTING)) {
        // Reuse existing connection
        return;
      }

      isConnectingRef.current = true;
      console.log('Attempting to connect to:', url);
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        console.log('WebSocket connected');
        setIsConnected(true);
        setError(null);
        reconnectAttempts.current = 0;
        isConnectingRef.current = false;
        
        // Join room as viewer or register as device
        if (deviceId) {
          // send using the raw socket to avoid race on wsRef
          try {
            ws.send(JSON.stringify({
              type: MessageTypes.DEVICE_REGISTER,
              deviceId,
              deviceInfo: {
                id: deviceId,
                name: `Device ${deviceId}`,
                type: 'android',
                isConnected: true,
                lastSeen: new Date(),
                isStreaming: false
              }
            }));
          } catch {}
        } else {
          try {
            ws.send(JSON.stringify({
              type: MessageTypes.VIEWER_JOIN
            }));
          } catch {}
        }
        
        callbacksRef.current.onOpen?.();
      };

      ws.onmessage = (event) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data);
          
          switch (message.type) {
            case MessageTypes.DEVICES_LIST:
              setDevices(message.devices || []);
              break;
              
            case MessageTypes.DEVICE_STATUS:
              console.log('📱 DEVICE_STATUS received:', message.device);
              setDevices(prev => {
                const updated = [...prev];
                const index = updated.findIndex(d => d.id === message.device.id);
                if (index >= 0) {
                  // Update existing device
                  if (message.device.isConnected) {
                    updated[index] = message.device;
                    console.log('✅ Device connected/updated:', message.device.id);
                  } else {
                    // Remove disconnected device
                    updated.splice(index, 1);
                    console.log('❌ Device disconnected, removed:', message.device.id);
                  }
                } else {
                  // Only add if device is connected
                  if (message.device.isConnected) {
                    updated.push(message.device);
                    console.log('➕ New device added:', message.device.id);
                  } else {
                    console.log('⚠️ Ignoring disconnected device:', message.device.id);
                  }
                }
                return updated;
              });
            // If currently viewing a device that just went offline/stop streaming, close modal
            try {
              if (!message.device.isConnected || !message.device.isStreaming) {
                // best-effort event; consumer may ignore
                callbacksRef.current.onMessage?.({ type: 'DEVICE_WENT_OFFLINE', deviceId: message.device.id } as any);
              }
            } catch {}
              break;

            case MessageTypes.DEVICE_SCREEN_INFO:
              if (message.deviceId && message.data) {
                setDevices(prev => prev.map(device => {
                  if (device.id === message.deviceId) {
                    return {
                      ...device,
                      screenWidth: message.data.width,
                      screenHeight: message.data.height
                    } as DeviceInfo;
                  }
                  return device;
                }));
                callbacksRef.current.onMessage?.({
                  type: MessageTypes.DEVICE_SCREEN_INFO,
                  deviceId: message.deviceId,
                  data: message.data
                } as any);
              }
              break;
              
            case MessageTypes.OFFER:
              console.log('📥 OFFER received from device:', message.deviceId);
              // Forward offer to callback for WebRTC handling
              callbacksRef.current.onMessage?.(message);
              break;
              
            case MessageTypes.ANSWER:
              console.log('📥 ANSWER received');
              // Forward answer to callback for WebRTC handling
              callbacksRef.current.onMessage?.(message);
              break;
              
            case MessageTypes.ICE_CANDIDATE:
              console.log('📥 ICE_CANDIDATE received from device:', message.deviceId);
              // Forward ICE candidate to callback for WebRTC handling
              callbacksRef.current.onMessage?.(message);
              break;
              
            case MessageTypes.ERROR:
              setError(message.message || 'Unknown error');
              break;
          }
          
          callbacksRef.current.onMessage?.(message);
        } catch (err) {
          console.error('Error parsing WebSocket message:', err);
          setError('Failed to parse message');
        }
      };

      ws.onclose = (event) => {
        console.log('WebSocket disconnected:', event.code, event.reason);
        setIsConnected(false);
        isConnectingRef.current = false;
        callbacksRef.current.onClose?.();
        
        // Only attempt to reconnect if it wasn't a manual close
        if (event.code !== 1000 && reconnectAttempts.current < maxReconnectAttempts) {
          reconnectAttempts.current++;
          const delay = Math.min(1000 * Math.pow(2, reconnectAttempts.current), 30000);
          
          console.log(`Attempting to reconnect in ${delay}ms (${reconnectAttempts.current}/${maxReconnectAttempts})...`);
          reconnectTimeoutRef.current = setTimeout(() => {
            connect();
          }, delay);
        } else if (reconnectAttempts.current >= maxReconnectAttempts) {
          setError('Failed to reconnect after multiple attempts');
        }
      };

      ws.onerror = (event) => {
        console.error('WebSocket error:', event);
        setError('WebSocket connection error');
        isConnectingRef.current = false;
        callbacksRef.current.onError?.(event);
      };

    } catch (err) {
      console.error('Error creating WebSocket connection:', err);
      setError('Failed to create WebSocket connection');
      isConnectingRef.current = false;
    }
  }, [url, deviceId]);

  const sendMessage = useCallback((message: WebSocketMessage) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      try {
        wsRef.current.send(JSON.stringify(message));
      } catch (err) {
        console.error('Error sending WebSocket message:', err);
        setError('Failed to send message');
      }
    } else {
      console.warn('WebSocket not connected, cannot send message');
      setError('WebSocket not connected');
    }
  }, []);

  const reconnect = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
    }
    reconnectAttempts.current = 0;
    setError(null);
    connect();
  }, [connect]);

  const disconnect = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (wsRef.current) {
      wsRef.current.close(1000, 'Manual disconnect');
      wsRef.current = null;
    }
    setIsConnected(false);
    reconnectAttempts.current = 0;
  }, []);

  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, [connect, disconnect]);

  return {
    sendMessage,
    isConnected,
    devices,
    error,
    reconnect
  };
};
