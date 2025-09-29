// WebRTC configuration
export const RTC_CONFIG = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
    { urls: 'stun:stun2.l.google.com:19302' }
  ]
};

// Re-export types from types module
export type { DeviceInfo, WebSocketMessage } from '../types';
export { MessageTypes } from '../types';

// WebRTC Peer Connection Manager
export class WebRTCManager {
  private peerConnections: Map<string, RTCPeerConnection> = new Map();
  private dataChannels: Map<string, RTCDataChannel> = new Map();
  private localStream: MediaStream | null = null;
  private remoteStreams: Map<string, MediaStream> = new Map();

  constructor() {
    this.setupEventListeners();
  }

  private setupEventListeners() {
    // Handle ICE candidates
    window.addEventListener('beforeunload', () => {
      this.cleanup();
    });
  }

  // Create peer connection for a specific device
  async createPeerConnection(deviceId: string, isInitiator: boolean = false): Promise<RTCPeerConnection> {
    const peerConnection = new RTCPeerConnection(RTC_CONFIG);
    
    // Handle ICE candidates
    peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        this.onIceCandidate(deviceId, event.candidate);
      }
    };

    // Handle remote stream
    peerConnection.ontrack = (event) => {
      console.log('Received remote stream from', deviceId);
      this.remoteStreams.set(deviceId, event.streams[0]);
      this.onRemoteStream(deviceId, event.streams[0]);
    };

    // Handle connection state changes
    peerConnection.onconnectionstatechange = () => {
      console.log(`Connection state for ${deviceId}:`, peerConnection.connectionState);
      if (peerConnection.connectionState === 'failed') {
        this.cleanupConnection(deviceId);
      }
    };

    this.peerConnections.set(deviceId, peerConnection);
    return peerConnection;
  }

  // Start screen sharing (for devices)
  async startScreenShare(): Promise<MediaStream> {
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          mediaSource: 'screen',
          width: { ideal: 1920 },
          height: { ideal: 1080 },
          frameRate: { ideal: 30 }
        },
        audio: true
      });

      this.localStream = stream;
      
      // Add tracks to all peer connections
      this.peerConnections.forEach((peerConnection, deviceId) => {
        stream.getTracks().forEach(track => {
          peerConnection.addTrack(track, stream);
        });
      });

      return stream;
    } catch (error) {
      console.error('Error starting screen share:', error);
      throw error;
    }
  }

  // Stop screen sharing
  stopScreenShare() {
    if (this.localStream) {
      this.localStream.getTracks().forEach(track => track.stop());
      this.localStream = null;
    }
  }

  // Create offer for peer connection
  async createOffer(deviceId: string): Promise<RTCSessionDescriptionInit> {
    const peerConnection = this.peerConnections.get(deviceId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    const offer = await peerConnection.createOffer({
      offerToReceiveAudio: true,
      offerToReceiveVideo: true
    });

    await peerConnection.setLocalDescription(offer);
    return offer;
  }

  // Create answer for peer connection
  async createAnswer(deviceId: string, offer: RTCSessionDescriptionInit): Promise<RTCSessionDescriptionInit> {
    const peerConnection = this.peerConnections.get(deviceId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    const answer = await peerConnection.createAnswer();
    await peerConnection.setLocalDescription(answer);
    return answer;
  }

  // Handle incoming offer
  async handleOffer(deviceId: string, offer: RTCSessionDescriptionInit) {
    let pc = this.peerConnections.get(deviceId);
    // Recreate peer if missing or in bad state (not stable and not closed)
    if (!pc || pc.signalingState === 'have-local-offer' || pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') {
      if (pc) {
        try { pc.close(); } catch {}
        this.peerConnections.delete(deviceId);
      }
      pc = await this.createPeerConnection(deviceId);
    }
    // Always set remote offer to (re)negotiate
    try {
      await pc.setRemoteDescription(offer);
    } catch (error) {
      console.error('Failed to set remote description, retrying with fresh peer connection', error);
      try { pc.close(); } catch {}
      this.peerConnections.delete(deviceId);
      const replacement = await this.createPeerConnection(deviceId);
      await replacement.setRemoteDescription(offer);
    }
  }

  // Handle incoming answer
  async handleAnswer(deviceId: string, answer: RTCSessionDescriptionInit) {
    const peerConnection = this.peerConnections.get(deviceId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    await peerConnection.setRemoteDescription(answer);
  }

  // Handle ICE candidate
  async handleIceCandidate(deviceId: string, candidate: RTCIceCandidateInit) {
    const peerConnection = this.peerConnections.get(deviceId);
    if (!peerConnection) {
      throw new Error('Peer connection not found');
    }

    await peerConnection.addIceCandidate(candidate);
  }

  // Get remote stream for a device
  getRemoteStream(deviceId: string): MediaStream | null {
    return this.remoteStreams.get(deviceId) || null;
  }

  // Get signaling state for a device
  getSignalingState(deviceId: string): RTCSdpType | string | null {
    const pc = this.peerConnections.get(deviceId);
    return pc ? pc.signalingState : null;
  }

  // Get all remote streams
  getAllRemoteStreams(): Map<string, MediaStream> {
    return this.remoteStreams;
  }

  // Cleanup specific connection
  cleanupConnection(deviceId: string) {
    const peerConnection = this.peerConnections.get(deviceId);
    if (peerConnection) {
      peerConnection.close();
      this.peerConnections.delete(deviceId);
    }

    const dataChannel = this.dataChannels.get(deviceId);
    if (dataChannel) {
      dataChannel.close();
      this.dataChannels.delete(deviceId);
    }

    this.remoteStreams.delete(deviceId);
  }

  // Cleanup all connections
  cleanup() {
    this.peerConnections.forEach(peerConnection => {
      peerConnection.close();
    });
    this.peerConnections.clear();

    this.dataChannels.forEach(dataChannel => {
      dataChannel.close();
    });
    this.dataChannels.clear();

    this.remoteStreams.clear();
    this.stopScreenShare();
  }

  // Event handlers (overridable or assignable by consumers)
  public onIceCandidate(deviceId: string, candidate: RTCIceCandidate) {
    // Override in parent class
  }

  public onRemoteStream(deviceId: string, stream: MediaStream) {
    // Override in parent class
  }

  public onAnswer(deviceId: string, answer: RTCSessionDescriptionInit) {
    // Override in parent class
  }
}
