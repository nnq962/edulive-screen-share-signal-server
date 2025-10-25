/**
 * Internal Audio Player
 * Receives Base64 encoded PCM audio from server
 * 
 * Note: Android now sends binary data to server for efficiency,
 * but server converts it back to Base64 for web compatibility.
 * Future: Add direct binary WebSocket support for full optimization.
 * 
 * Current bandwidth savings: ~26% on Android→Server link
 * Decodes and plays using Web Audio API
 */

export class InternalAudioPlayer {
  private audioContext: AudioContext | null = null
  private audioQueue: Float32Array[] = []
  private isPlaying = false
  private nextPlayTime = 0
  private readonly BUFFER_SIZE = 4096
  private readonly SAMPLE_RATE = 44100
  private readonly CHANNELS = 2

  constructor() {
    // AudioContext will be created on first audio data
    console.log('🎵 [AudioPlayer] InternalAudioPlayer initialized with binary support')
  }

  private initAudioContext() {
    if (this.audioContext) return

    try {
      this.audioContext = new AudioContext({ sampleRate: this.SAMPLE_RATE })
      this.nextPlayTime = this.audioContext.currentTime
      console.log('🎵 AudioContext initialized:', {
        sampleRate: this.audioContext.sampleRate,
        state: this.audioContext.state
      })
    } catch (error) {
      console.error('❌ Failed to initialize AudioContext:', error)
    }
  }

  /**
   * Process binary audio data from WebSocket (NEW - Full Binary Protocol)
   * Binary protocol: Version(1) | MessageType(1) | SampleRate(2) | Channels(1) | DeviceIdLen(1) | PayloadLen(4) | Reserved(2) | DeviceId(variable) | AudioData(variable)
   */
  processBinaryAudioData(buffer: ArrayBuffer, deviceId?: string) {
    console.log('🎵 [AudioPlayer] processBinaryAudioData called:', {
      bufferLength: buffer.byteLength,
      deviceId,
      hasAudioContext: !!this.audioContext
    })

    if (!this.audioContext) {
      console.log('🎵 [AudioPlayer] Initializing AudioContext...')
      this.initAudioContext()
    }

    if (!this.audioContext) {
      console.error('❌ [AudioPlayer] AudioContext failed to initialize')
      return
    }

    try {
      if (buffer.byteLength < 12) {
        console.error('❌ [AudioPlayer] Binary buffer too short:', buffer.byteLength)
        return
      }

      const dataView = new DataView(buffer)
      let offset = 0

      // Parse binary protocol header
      const version = dataView.getUint8(offset++)
      const messageType = dataView.getUint8(offset++)
      const sampleRate = dataView.getUint16(offset, true); offset += 2 // little-endian
      const channels = dataView.getUint8(offset++)
      const deviceIdLen = dataView.getUint8(offset++)
      const payloadLen = dataView.getUint32(offset, true); offset += 4 // little-endian

      // Skip reserved bytes
      offset += 2

      if (buffer.byteLength < 12 + deviceIdLen + payloadLen) {
        console.error('❌ [AudioPlayer] Buffer length mismatch:', buffer.byteLength, 'expected:', 12 + deviceIdLen + payloadLen)
        return
      }

      // Extract device ID (optional for validation)
      const extractedDeviceId = new TextDecoder().decode(new Uint8Array(buffer, offset, deviceIdLen))
      offset += deviceIdLen

      console.log('🎵 [AudioPlayer] Binary audio parsed:', {
        version,
        messageType,
        sampleRate,
        channels,
        deviceId: extractedDeviceId,
        payloadBytes: payloadLen
      })

      // Extract raw PCM data (Int16Array)
      const audioData = new Int16Array(buffer, offset, payloadLen / 2) // 2 bytes per Int16

      // Convert Int16 to Float32 directly (no Base64 decode step!)
      const float32Array = new Float32Array(audioData.length)
      for (let i = 0; i < audioData.length; i++) {
        float32Array[i] = audioData[i] / 32768.0
      }

      // Add to queue
      this.audioQueue.push(float32Array)
      console.log('🎵 [AudioPlayer] Binary audio added to queue, queue length:', this.audioQueue.length)

      // Start playing if not already
      if (!this.isPlaying) {
        console.log('🎵 [AudioPlayer] Starting binary audio playback...')
        this.isPlaying = true
        this.playNextBuffer()
      } else {
        console.log('🎵 [AudioPlayer] Binary audio queued, already playing')
      }

    } catch (error) {
      console.error('❌ [AudioPlayer] Error processing binary audio data:', error)
      if (error instanceof Error) {
        console.error('❌ [AudioPlayer] Error details:', {
          message: error.message,
          stack: error.stack
        })
      }
    }
  }

  /**
   * Process incoming audio data from WebSocket
   */
  processAudioData(base64Data: string, sampleRate: number, channels: number) {
    console.log('🎵 [AudioPlayer] processAudioData called:', {
      base64Length: base64Data?.length || 0,
      sampleRate,
      channels,
      hasAudioContext: !!this.audioContext
    })

    if (!this.audioContext) {
      console.log('🎵 [AudioPlayer] Initializing AudioContext...')
      this.initAudioContext()
    }

    if (!this.audioContext) {
      console.error('❌ [AudioPlayer] AudioContext failed to initialize')
      return
    }

    try {
      console.log('🎵 [AudioPlayer] Decoding base64 audio data...')
      // Decode Base64 to binary
      const binaryString = atob(base64Data)
      const len = binaryString.length
      const bytes = new Uint8Array(len)
      
      for (let i = 0; i < len; i++) {
        bytes[i] = binaryString.charCodeAt(i)
      }

      console.log('🎵 [AudioPlayer] Converting to Int16Array, bytes:', len)
      // Convert bytes to Int16Array (PCM 16-bit)
      const int16Array = new Int16Array(bytes.buffer)
      
      console.log('🎵 [AudioPlayer] Converting to Float32Array, samples:', int16Array.length)
      // Convert Int16 to Float32 (-1.0 to 1.0)
      const float32Array = new Float32Array(int16Array.length)
      for (let i = 0; i < int16Array.length; i++) {
        float32Array[i] = int16Array[i] / 32768.0
      }

      // Add to queue
      this.audioQueue.push(float32Array)
      console.log('🎵 [AudioPlayer] Added to queue, queue length:', this.audioQueue.length)

      // Start playing if not already
      if (!this.isPlaying) {
        console.log('🎵 [AudioPlayer] Starting playback...')
        this.isPlaying = true
        this.playNextBuffer()
      } else {
        console.log('🎵 [AudioPlayer] Already playing, audio will be queued')
      }

    } catch (error) {
      console.error('❌ [AudioPlayer] Error processing audio data:', error)
      if (error instanceof Error) {
        console.error('❌ [AudioPlayer] Error details:', {
          message: error.message,
          stack: error.stack
        })
      }
    }
  }

  private playNextBuffer() {
    if (!this.audioContext) {
      console.warn('⚠️ [AudioPlayer] No AudioContext in playNextBuffer')
      this.isPlaying = false
      return
    }

    if (this.audioQueue.length === 0) {
      console.log('🎵 [AudioPlayer] Queue empty, stopping playback')
      this.isPlaying = false
      return
    }

    try {
      const audioData = this.audioQueue.shift()!
      const samplesPerChannel = audioData.length / this.CHANNELS

      console.log('🎵 [AudioPlayer] Creating audio buffer:', {
        channels: this.CHANNELS,
        samplesPerChannel,
        sampleRate: this.SAMPLE_RATE,
        audioContextState: this.audioContext.state
      })

      // Create audio buffer
      const buffer = this.audioContext.createBuffer(
        this.CHANNELS,
        samplesPerChannel,
        this.SAMPLE_RATE
      )

      // Fill buffer with de-interleaved audio data
      for (let channel = 0; channel < this.CHANNELS; channel++) {
        const channelData = buffer.getChannelData(channel)
        for (let i = 0; i < samplesPerChannel; i++) {
          channelData[i] = audioData[i * this.CHANNELS + channel]
        }
      }

      // Create source and play
      const source = this.audioContext.createBufferSource()
      source.buffer = buffer
      source.connect(this.audioContext.destination)

      // Schedule playback
      const currentTime = this.audioContext.currentTime
      const playTime = Math.max(currentTime, this.nextPlayTime)
      
      console.log('🎵 [AudioPlayer] Starting audio source:', {
        currentTime,
        playTime,
        nextPlayTime: this.nextPlayTime
      })
      
      source.start(playTime)
      
      // Update next play time
      const bufferDuration = samplesPerChannel / this.SAMPLE_RATE
      this.nextPlayTime = playTime + bufferDuration

      console.log('🎵 [AudioPlayer] Audio source started, duration:', bufferDuration, 's')

      // Play next buffer
      source.onended = () => {
        console.log('🎵 [AudioPlayer] Audio source ended, playing next...')
        this.playNextBuffer()
      }

    } catch (error) {
      console.error('❌ [AudioPlayer] Error playing audio buffer:', error)
      if (error instanceof Error) {
        console.error('❌ [AudioPlayer] Error details:', {
          message: error.message,
          stack: error.stack
        })
      }
      this.isPlaying = false
    }
  }

  /**
   * Clear audio queue and stop playback
   */
  clear() {
    this.audioQueue = []
    this.isPlaying = false
    
    if (this.audioContext) {
      this.nextPlayTime = this.audioContext.currentTime
    }
    
    console.log('🧹 Audio queue cleared')
  }

  /**
   * Close audio context and cleanup
   */
  async close() {
    this.clear()
    
    if (this.audioContext) {
      try {
        await this.audioContext.close()
        console.log('🛑 AudioContext closed')
      } catch (error) {
        console.warn('⚠️ Error closing AudioContext:', error)
      }
      this.audioContext = null
    }
  }

  /**
   * Get current state
   */
  getState() {
    return {
      isPlaying: this.isPlaying,
      queueLength: this.audioQueue.length,
      audioContextState: this.audioContext?.state || 'closed',
      currentTime: this.audioContext?.currentTime || 0,
      nextPlayTime: this.nextPlayTime
    }
  }

  /**
   * Resume audio context (needed after user interaction)
   */
  async resume() {
    if (this.audioContext && this.audioContext.state === 'suspended') {
      try {
        await this.audioContext.resume()
        console.log('▶️ AudioContext resumed')
      } catch (error) {
        console.error('❌ Failed to resume AudioContext:', error)
      }
    }
  }
}
