import { Typography, Alert, Space, Row, Col, Card, Modal, Tag, Layout, Spin } from 'antd'
import { CheckCircleTwoTone } from '@ant-design/icons'
import { useWebSocket } from './hooks/useWebSocket'
import { WebRTCManager } from './utils/webrtc'
import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react'

const { Text } = Typography

type PointerAction = 'DOWN' | 'MOVE' | 'UP' | 'CANCEL'

interface PointerState {
  pointerId: number
  pointerType: string
  startX: number
  startY: number
  lastX: number
  lastY: number
  lastSentX: number
  lastSentY: number
  lastSentTime: number
  downTime: number
}

// Reduced constants for mouse-only control
const MOUSE_MOVE_THROTTLE_MS = 32 // Send MOVE at most every 32ms (30fps)
const SCROLL_POINTER_ID = -1
const SCROLL_RELEASE_TIMEOUT_MS = 150

function App() {
  // Get the current hostname/IP from the browser
  const hostname = window.location.hostname
  const wsUrl = `ws://${hostname}:3001/ws`

  // WebRTC Manager
  const webrtcManagerRef = useRef<WebRTCManager | null>(null)
  const [remoteStream, setRemoteStream] = useState<MediaStream | null>(null)
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null)
  const [isModalOpen, setIsModalOpen] = useState(false)
  const videoRef = useRef<HTMLVideoElement>(null)
  const attachRetryRef = useRef<number | null>(null)
  const requestedDeviceRef = useRef<string | null>(null)
  const pendingRequestRef = useRef<string | null>(null)
  const [videoSize, setVideoSize] = useState<{ width: number; height: number } | null>(null)
  const [isStreamReady, setIsStreamReady] = useState(false)
  const [physicalSize, setPhysicalSize] = useState<{ width: number; height: number } | null>(null)
  const [isOrientationChanging, setIsOrientationChanging] = useState(false)
  const [videoKey, setVideoKey] = useState(0) // Force re-render when container size changes
  const videoContainerRef = useRef<HTMLDivElement>(null)
  const pointerStatesRef = useRef<Map<number, PointerState>>(new Map())

  // WebSocket connection
  const {
    isConnected,
    devices,
    error,
    sendMessage,
  } = useWebSocket({
    url: wsUrl,
    onMessage: handleWebSocketMessage,
    onError: handleWebSocketError,
    onOpen: handleWebSocketOpen,
    onClose: handleWebSocketClose
  })

  // Initialize WebRTC Manager
  useEffect(() => {
    if (!webrtcManagerRef.current) {
      webrtcManagerRef.current = new WebRTCManager()

      webrtcManagerRef.current.onRemoteStream = (deviceId: string, stream: MediaStream) => {
        if (deviceId === selectedDeviceId) {
          setRemoteStream(stream)
        }
      }

      webrtcManagerRef.current.onIceCandidate = (deviceId: string, candidate: RTCIceCandidate) => {
        try {
          sendMessage({
            type: 'ICE_CANDIDATE',
            deviceId,
            candidate
          } as any)
          console.log('[WEB][ICE] sent candidate to server for device:', deviceId)
        } catch (e) {
          console.error('[WEB][ICE] failed to send candidate', e)
        }
      }
    }
    return () => {
      webrtcManagerRef.current?.cleanup()
    }
  }, [selectedDeviceId])

  useEffect(() => {
    if (videoRef.current && remoteStream) {
      console.log('🎥 Re-attaching stream to video element')
      videoRef.current.srcObject = remoteStream
      const play = async () => {
        try {
          await videoRef.current?.play()
          console.log('🎥 Video play successful after re-attach')
        } catch (e) {
          console.warn('Tự phát video bị chặn, sẽ phát khi người dùng tương tác', e)
        }
      }
      play()
    } else if (videoRef.current && !remoteStream) {
      console.warn('🎥 Video element exists but no remoteStream available')
      // Try to get remoteStream from WebRTC manager
      if (selectedDeviceId && webrtcManagerRef.current) {
        const stream = webrtcManagerRef.current.getRemoteStream(selectedDeviceId)
        if (stream) {
          console.log('🎥 Found remoteStream from WebRTC manager, attaching...')
          videoRef.current.srcObject = stream
          const play = async () => {
            try {
              await videoRef.current?.play()
              console.log('🎥 Video play successful after re-attach from WebRTC manager')
            } catch (e) {
              console.warn('Tự phát video bị chặn, sẽ phát khi người dùng tương tác', e)
            }
          }
          play()
        } else {
          console.warn('🎥 No remoteStream found in WebRTC manager either')
        }
      }
    }
  }, [remoteStream, selectedDeviceId, videoKey]) // Re-attach when video element is re-rendered


  const sendStreamRequest = useCallback((deviceId: string) => {
    if (!isConnected) return
    if (requestedDeviceRef.current === deviceId) return
    try {
      sendMessage({
        type: 'REQUEST_STREAM',
        deviceId
      } as any)
      requestedDeviceRef.current = deviceId
      console.log('[WEB][Request] REQUEST_STREAM sent for', deviceId)
      setIsStreamReady(false)
      setVideoSize(null)
    } catch (error) {
      console.error('[WEB][Request] Failed to request stream', error)
    }
  }, [isConnected, sendMessage])

  useEffect(() => {
    if (!selectedDeviceId || !isConnected) {
      return
    }

    const device = devices.find(d => d.id === selectedDeviceId && d.isConnected)
    if (device) {
      sendStreamRequest(selectedDeviceId)
      pendingRequestRef.current = null
    } else {
      pendingRequestRef.current = selectedDeviceId
      console.log('[WEB][Request] Waiting for device to be online before requesting', selectedDeviceId)
    }

    return () => {
      const currentId = selectedDeviceId
      const wasRequested = requestedDeviceRef.current === currentId
      if (wasRequested && isConnected) {
        try {
          sendMessage({
            type: 'STOP_STREAM',
            deviceId: currentId
          } as any)
          console.log('[WEB][Request] STOP_STREAM sent for', currentId)
        } catch (error) {
          console.error('[WEB][Request] Failed to stop stream', error)
        }
      }
      if (requestedDeviceRef.current === currentId) {
        requestedDeviceRef.current = null
      }
      if (pendingRequestRef.current === currentId) {
        pendingRequestRef.current = null
      }
      setIsStreamReady(false)
      setVideoSize(null)
    }
  }, [selectedDeviceId, isConnected, sendMessage])

  useEffect(() => {
    if (!isConnected) return
    const pendingId = pendingRequestRef.current
    if (!pendingId) return
    const device = devices.find(d => d.id === pendingId && d.isConnected)
    if (!device) return
    sendStreamRequest(pendingId)
    pendingRequestRef.current = null
  }, [devices, isConnected, sendStreamRequest])

  useEffect(() => {
    if (!selectedDeviceId) {
      setPhysicalSize(null)
      return
    }
    const device = devices.find(d => d.id === selectedDeviceId)
    if (device?.screenWidth && device?.screenHeight) {
      setPhysicalSize({ width: device.screenWidth, height: device.screenHeight })
    }
  }, [selectedDeviceId, devices])

  function handleWebSocketMessage(message: any) {
    console.log('WebSocket message received:', message)

    switch (message.type) {
      case 'DEVICE_WENT_OFFLINE': {
        const wentOfflineId = message.deviceId
        if (selectedDeviceId && wentOfflineId === selectedDeviceId) {
          setIsModalOpen(false)
          setRemoteStream(null)
          setSelectedDeviceId(null)
          if (requestedDeviceRef.current === wentOfflineId) {
            requestedDeviceRef.current = null
          }
          if (pendingRequestRef.current === wentOfflineId) {
            pendingRequestRef.current = null
          }
          setIsStreamReady(false)
          setVideoSize(null)
        }
        break
      }
      case 'OFFER':
        console.log('🎯 Processing OFFER from device:', message.deviceId)
        handleOffer(message.deviceId, message.offer)
        break

      case 'ANSWER':
        console.log('🎯 Processing ANSWER')
        handleAnswer(message.deviceId, message.answer)
        break

      case 'ICE_CANDIDATE':
        console.log('🎯 Processing ICE_CANDIDATE from device:', message.deviceId)
        handleIceCandidate(message.deviceId, message.candidate)
        break

      case 'STOP_STREAM':
        if (selectedDeviceId && message.deviceId === selectedDeviceId) {
          console.log('🛑 STOP_STREAM received for current device, closing viewer')
          setRemoteStream(null)
          setIsModalOpen(false)
          setSelectedDeviceId(null)
          if (requestedDeviceRef.current === message.deviceId) {
            requestedDeviceRef.current = null
          }
          if (pendingRequestRef.current === message.deviceId) {
            pendingRequestRef.current = null
          }
          setIsStreamReady(false)
          setVideoSize(null)
          setPhysicalSize(null)
        }
        break

      case 'DEVICE_SCREEN_INFO':
        console.log('📱 Received DEVICE_SCREEN_INFO:', message)
        if (selectedDeviceId && message.deviceId === selectedDeviceId && message.data) {
          const newPhysicalSize = { width: message.data.width, height: message.data.height }
          console.log('📱 Screen size updated:', newPhysicalSize, 'for device:', selectedDeviceId)
          
          // Check if this is a significant size change (orientation change)
          const currentSize = physicalSize
          const isOrientationChange = currentSize && 
            (Math.abs(newPhysicalSize.width - currentSize.width) > 100 || 
             Math.abs(newPhysicalSize.height - currentSize.height) > 100)
          
          if (isOrientationChange) {
            console.log('🔄 Orientation change detected, updating UI...')
            console.log('🔄 Current size:', currentSize, 'New size:', newPhysicalSize)
            // Show orientation changing state
            setIsOrientationChanging(true)
            setIsStreamReady(false)
            
            // Clear pointer states to avoid coordinate mapping issues
            clearAllPointerStates()
            
            // Update physical size first
            setPhysicalSize(newPhysicalSize)
            console.log('🔄 Physical size updated to:', newPhysicalSize)
            
            // Clear video size to force using physical size (but keep it for now to avoid black screen)
            // setVideoSize(null)
            // console.log('🔄 Video size cleared to force using physical size')
            
            // Brief delay to allow UI to update, then restore stream ready state
            setTimeout(() => {
              // Check if stream is still available before restoring ready state
              const hasStream = videoRef.current?.srcObject || (selectedDeviceId && webrtcManagerRef.current?.getRemoteStream(selectedDeviceId))
              if (hasStream) {
                setIsOrientationChanging(false)
                setIsStreamReady(true)
                console.log('🔄 Orientation change UI update completed - stream preserved')
              } else {
                console.warn('🔄 Stream lost during orientation change, keeping loading state')
                // Try to re-request stream
                if (selectedDeviceId) {
                  console.log('🔄 Attempting to re-request stream...')
                  sendStreamRequest(selectedDeviceId)
                }
              }
            }, 200)
          } else {
            // Minor size change, just update without loading state
            console.log('📏 Minor size change, updating without loading state')
            setPhysicalSize(newPhysicalSize)
          }
        }
        break
    }
  }

  async function handleOffer(deviceId: string, offer: any) {
    try {
      if (!webrtcManagerRef.current) return

      console.log('📥 Handling OFFER from device:', deviceId)

      const offerObj: RTCSessionDescriptionInit = {
        type: 'offer',
        sdp: offer
      }

      const state = webrtcManagerRef.current.getSignalingState(deviceId)
      if (state === 'stable') {
        console.log('[WEB][Offer] Resetting peer connection for device', deviceId)
        webrtcManagerRef.current.cleanupConnection(deviceId)
        if (deviceId === selectedDeviceId) {
          setRemoteStream(null)
        }
      }
      if (state && state !== 'have-remote-offer' && state !== 'stable') {
        console.warn('OFFER bị bỏ qua do signalingState:', state)
        webrtcManagerRef.current.cleanupConnection(deviceId)
      }

      await webrtcManagerRef.current.handleOffer(deviceId, offerObj)

      const answer = await webrtcManagerRef.current.createAnswer(deviceId, offerObj)
      sendMessage({
        type: 'ANSWER',
        deviceId: deviceId,
        answer: answer.sdp
      })
      console.log('📤 ANSWER sent to device:', deviceId)
    } catch (error) {
      console.error('❌ Error handling offer:', error)
    }
  }

  async function handleAnswer(deviceId: string, answer: any) {
    try {
      if (!webrtcManagerRef.current) return

      console.log('📥 Handling ANSWER from device:', deviceId)

      const answerObj: RTCSessionDescriptionInit = {
        type: 'answer',
        sdp: answer
      }

      await webrtcManagerRef.current.handleAnswer(deviceId, answerObj)
    } catch (error) {
      console.error('❌ Error handling answer:', error)
    }
  }

  async function handleIceCandidate(deviceId: string, candidate: any) {
    try {
      if (!webrtcManagerRef.current) return

      console.log('📥 Handling ICE_CANDIDATE from device:', deviceId)

      let candidateObj: RTCIceCandidateInit

      if (typeof candidate === 'string') {
        try {
          const parsed = JSON.parse(candidate)
          const cand = parsed.candidate ?? parsed.sdp ?? candidate
          candidateObj = {
            candidate: cand,
            sdpMLineIndex: parsed.sdpMLineIndex ?? parsed.mlineindex ?? parsed.mLineIndex,
            sdpMid: parsed.sdpMid ?? parsed.mid
          }
        } catch {
          candidateObj = {
            candidate: candidate
          }
        }
      } else {
        const obj = candidate as any
        candidateObj = {
          candidate: obj.candidate ?? obj.sdp ?? '',
          sdpMLineIndex: obj.sdpMLineIndex ?? obj.mlineindex ?? obj.mLineIndex,
          sdpMid: obj.sdpMid ?? obj.mid
        }
      }

      await webrtcManagerRef.current.handleIceCandidate(deviceId, candidateObj)
    } catch (error) {
      console.error('❌ Error handling ICE candidate:', error)
    }
  }

  function handleWebSocketError(error: Event) {
    console.error('WebSocket error:', error)
  }

  function handleWebSocketOpen() {
    console.log('WebSocket connected')
  }

  function handleWebSocketClose() {
    console.log('WebSocket disconnected')
  }

  const connectedDevices = devices.filter(d => d.isConnected).length

  function openDeviceModal(deviceId: string) {
    setSelectedDeviceId(deviceId)
    const stream = webrtcManagerRef.current?.getRemoteStream(deviceId) || null
    setRemoteStream(stream)
    setIsModalOpen(true)
    setIsStreamReady(!!stream)
    if (stream && videoRef.current?.videoWidth && videoRef.current?.videoHeight) {
      setVideoSize({ width: videoRef.current.videoWidth, height: videoRef.current.videoHeight })
    }
  }

  useEffect(() => {
    if (!remoteStream) {
      setIsStreamReady(false)
      setVideoSize(null)
    }
    if (remoteStream) {
      const [track] = remoteStream.getVideoTracks()
      if (track && typeof track.getSettings === 'function') {
        const settings = track.getSettings()
        if (settings.width && settings.height) {
          setVideoSize({ width: settings.width, height: settings.height })
          if (!physicalSize) {
            setPhysicalSize({ width: settings.width, height: settings.height })
          }
        }
      }
    }
  }, [remoteStream, physicalSize])

  // When physicalSize is available (from DEVICE_SCREEN_INFO), prioritize it over videoSize
  // This ensures correct display when orientation changes
  const baseSize = physicalSize ?? videoSize

  const renderedVideoSize = useMemo(() => {
    if (!baseSize) return null
    console.log('🎨 Calculating rendered size from baseSize:', baseSize, 'videoSize:', videoSize, 'physicalSize:', physicalSize)
    const maxWidth = window.innerWidth * 0.9
    const maxHeight = window.innerHeight * 0.8
    const scale = Math.min(maxWidth / baseSize.width, maxHeight / baseSize.height, 1)
    const result = {
      width: Math.round(baseSize.width * scale),
      height: Math.round(baseSize.height * scale)
    }
    console.log('🎨 Rendered size calculated:', result, 'scale:', scale, 'maxWidth:', maxWidth, 'maxHeight:', maxHeight)
    return result
  }, [baseSize, videoSize, physicalSize])

  const displayWidth = renderedVideoSize?.width ?? baseSize?.width ?? 320
  const displayHeight = renderedVideoSize?.height ?? baseSize?.height ?? 568

  // Additional effect to ensure video element is properly sized when physicalSize changes
  useEffect(() => {
    if (videoRef.current && physicalSize && isStreamReady) {
      console.log('🎥 Physical size changed, ensuring video element is properly sized')
      console.log('🎥 Container size:', { width: displayWidth, height: displayHeight })
      console.log('🎥 Physical size:', physicalSize)
      console.log('🎥 Video element size:', { 
        width: videoRef.current.offsetWidth, 
        height: videoRef.current.offsetHeight 
      })
      
      // Force a re-layout by temporarily changing and restoring the video element
      const video = videoRef.current
      const currentSrc = video.srcObject
      if (currentSrc) {
        // Trigger a re-layout without losing the stream
        video.style.width = '99%'
        setTimeout(() => {
          video.style.width = '100%'
          console.log('🎥 Video element re-layout completed')
        }, 10)
      }
    }
  }, [physicalSize, isStreamReady, displayWidth, displayHeight])

  // Force video element to re-render when container size changes
  useEffect(() => {
    if (isStreamReady && (displayWidth > 0 && displayHeight > 0)) {
      console.log('🎥 Container size changed, forcing video re-render')
      setVideoKey(prev => prev + 1)
    }
  }, [displayWidth, displayHeight, isStreamReady])

  // Add smooth transition for video size changes
  const videoContainerStyle = useMemo(() => ({
    background: '#000',
    position: 'relative' as const,
    overflow: 'hidden',
    minWidth: displayWidth,
    minHeight: displayHeight,
    width: displayWidth,
    height: displayHeight,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: isStreamReady ? 'pointer' : 'default',
    touchAction: 'none' as const,
    outline: 'none',
    borderRadius: '12px', // Bo tròn 4 góc
    transition: 'width 0.3s ease, height 0.3s ease', // Smooth transition
    boxShadow: '0 4px 12px rgba(0, 0, 0, 0.3)' // Add shadow for depth
  }), [displayWidth, displayHeight, isStreamReady])

  const dispatchPointerCommand = useCallback((command: {
    type: 'POINTER'
    action: PointerAction
    pointerId: number
    pointerType: string
    x: number
    y: number
    normalizedX: number
    normalizedY: number
    durationMs: number
  }) => {
    if (!selectedDeviceId) return
    try {
      sendMessage({
        type: 'CONTROL_COMMAND',
        deviceId: selectedDeviceId,
        data: command
      } as any)
      
      if (command.action === 'DOWN' || command.action === 'UP') {
        console.log(`[WEB][Mouse] ${command.action} at (${command.x}, ${command.y})`)
      }
    } catch (error) {
      console.error('[WEB][Control] Failed to send pointer command', error)
    }
  }, [selectedDeviceId, sendMessage])

  const clearAllPointerStates = useCallback(() => {
    pointerStatesRef.current.forEach((state, id) => {
      dispatchPointerCommand({
        type: 'POINTER',
        action: 'CANCEL',
        pointerId: id,
        pointerType: state.pointerType,
        x: state.lastX,
        y: state.lastY,
        normalizedX: state.lastX / (physicalSize?.width ?? 1),
        normalizedY: state.lastY / (physicalSize?.height ?? 1),
        durationMs: 8
      })
    })
    pointerStatesRef.current.clear()
  }, [dispatchPointerCommand, physicalSize])

  useEffect(() => {
    if (!isStreamReady) {
      clearAllPointerStates()
    }
  }, [isStreamReady, clearAllPointerStates])

  useEffect(() => () => {
    clearAllPointerStates()
  }, [clearAllPointerStates])

  useEffect(() => {
    if (isModalOpen && isStreamReady) {
      try {
        videoContainerRef.current?.focus()
      } catch (error) {
        console.warn('[WEB][Control] Unable to focus video container', error)
      }
    }
  }, [isModalOpen, isStreamReady])

  const handleMouseDown = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    if (!isStreamReady || !selectedDeviceId || !baseSize || !videoContainerRef.current) return
    
    // Only handle left mouse button
    if (event.button !== 0) return

    const bounds = videoContainerRef.current.getBoundingClientRect()
    if (bounds.width === 0 || bounds.height === 0) return

    const pointerId = 1 // Fixed pointer ID for mouse
    const existingState = pointerStatesRef.current.get(pointerId)
    if (existingState) {
      pointerStatesRef.current.delete(pointerId)
    }
    
    const rawXRatio = (event.clientX - bounds.left) / bounds.width
    const rawYRatio = (event.clientY - bounds.top) / bounds.height
    const xRatio = Math.min(Math.max(rawXRatio, 0), 1)
    const yRatio = Math.min(Math.max(rawYRatio, 0), 1)
    
    // Always use physicalSize if available for accurate mapping
    const targetSize = physicalSize ?? baseSize
    if (!targetSize) {
      console.warn('[WEB][Mouse] No target size available for coordinate mapping')
      return
    }
    
    const x = Math.round(targetSize.width * xRatio)
    const y = Math.round(targetSize.height * yRatio)
    const now = Date.now()

    console.log(`[WEB][Mouse] DOWN at screen (${x}, ${y}) from ratio (${xRatio.toFixed(3)}, ${yRatio.toFixed(3)}) with target size ${targetSize.width}x${targetSize.height}`)

    const pointerState: PointerState = {
      pointerId,
      pointerType: 'mouse',
      startX: x,
      startY: y,
      lastX: x,
      lastY: y,
      lastSentX: x,
      lastSentY: y,
      lastSentTime: now,
      downTime: now
    }
    pointerStatesRef.current.set(pointerId, pointerState)

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'DOWN',
      pointerId,
      pointerType: 'mouse',
      x,
      y,
      normalizedX: xRatio,
      normalizedY: yRatio,
      durationMs: 16
    })

    event.preventDefault()
  }, [isStreamReady, selectedDeviceId, baseSize, physicalSize, dispatchPointerCommand])

  const handleMouseMove = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const state = pointerStatesRef.current.get(1) // Mouse pointer ID is always 1
    if (!state || !videoContainerRef.current || !baseSize) return

    const bounds = videoContainerRef.current.getBoundingClientRect()
    if (bounds.width === 0 || bounds.height === 0) return

    // Always use physicalSize if available for accurate mapping
    const targetSize = physicalSize ?? baseSize
    if (!targetSize) {
      console.warn('[WEB][Mouse] No target size available for coordinate mapping')
      return
    }
    
    const rawXRatio = (event.clientX - bounds.left) / bounds.width
    const rawYRatio = (event.clientY - bounds.top) / bounds.height
    const xRatio = Math.min(Math.max(rawXRatio, 0), 1)
    const yRatio = Math.min(Math.max(rawYRatio, 0), 1)
    const x = Math.round(targetSize.width * xRatio)
    const y = Math.round(targetSize.height * yRatio)

    state.lastX = x
    state.lastY = y

    const now = Date.now()
    const timeSinceLastSend = now - state.lastSentTime
    
    // Throttle MOVE events - only send if enough time has passed
    if (timeSinceLastSend < MOUSE_MOVE_THROTTLE_MS) {
      return
    }

    // Calculate distance moved
    const dx = x - state.lastSentX
    const dy = y - state.lastSentY
    const distance = Math.sqrt(dx * dx + dy * dy)
    
    // Only send if moved at least 3 pixels
    if (distance < 3) {
      return
    }

    state.lastSentX = x
    state.lastSentY = y
    state.lastSentTime = now

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'MOVE',
      pointerId: 1,
      pointerType: state.pointerType,
      x,
      y,
      normalizedX: xRatio,
      normalizedY: yRatio,
      durationMs: Math.round(timeSinceLastSend)
    })

    event.preventDefault()
  }, [baseSize, physicalSize, dispatchPointerCommand])

  const handleMouseUp = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const state = pointerStatesRef.current.get(1)
    if (!state) return

    const x = state.lastX
    const y = state.lastY
    
    // Always use physicalSize if available for accurate mapping
    const targetSize = physicalSize ?? baseSize
    if (!targetSize) {
      console.warn('[WEB][Mouse] No target size available for coordinate mapping')
      pointerStatesRef.current.delete(1)
      return
    }
    
    const xRatio = x / targetSize.width
    const yRatio = y / targetSize.height
    const now = Date.now()
    const duration = Math.max(8, now - state.lastSentTime)

    console.log(`[WEB][Mouse] UP at screen (${x}, ${y}) with target size ${targetSize.width}x${targetSize.height}`)

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'UP',
      pointerId: 1,
      pointerType: state.pointerType,
      x,
      y,
      normalizedX: xRatio,
      normalizedY: yRatio,
      durationMs: duration
    })

    pointerStatesRef.current.delete(1)
    event.preventDefault()
  }, [dispatchPointerCommand, physicalSize, baseSize])

  const handleWheel = useCallback((event: React.WheelEvent<HTMLDivElement>) => {
    if (!isStreamReady || !selectedDeviceId || !videoContainerRef.current || !baseSize) {
      return
    }

    event.preventDefault()
    event.stopPropagation()

    const bounds = videoContainerRef.current.getBoundingClientRect()
    if (bounds.width === 0 || bounds.height === 0) {
      return
    }

    // Always use physicalSize if available for accurate mapping
    const targetSize = physicalSize ?? baseSize
    if (!targetSize) {
      console.warn('[WEB][Wheel] No target size available for coordinate mapping')
      return
    }
    
    const pointerId = SCROLL_POINTER_ID
    let state = pointerStatesRef.current.get(pointerId)

    if (!state) {
      const rawXRatio = (event.clientX - bounds.left) / bounds.width
      const rawYRatio = (event.clientY - bounds.top) / bounds.height
      const xRatio = Math.min(Math.max(rawXRatio, 0), 1)
      const yRatio = Math.min(Math.max(rawYRatio, 0), 1)
      const x = Math.round(targetSize.width * xRatio)
      const y = Math.round(targetSize.height * yRatio)
      const now = Date.now()
      
      state = {
        pointerId,
        pointerType: 'wheel',
        startX: x,
        startY: y,
        lastX: x,
        lastY: y,
        lastSentX: x,
        lastSentY: y,
        lastSentTime: now,
        downTime: now
      }
      pointerStatesRef.current.set(pointerId, state)

      dispatchPointerCommand({
        type: 'POINTER',
        action: 'DOWN',
        pointerId,
        pointerType: 'wheel',
        x,
        y,
        normalizedX: xRatio,
        normalizedY: yRatio,
        durationMs: 16
      })
    }

    const deltaMode = event.deltaMode ?? 0
    const modeScale = deltaMode === 1 ? 16 : deltaMode === 2 ? bounds.height : 1
    const deltaX = event.deltaX * modeScale
    const deltaY = event.deltaY * modeScale

    const scaleX = targetSize.width / bounds.width
    const scaleY = targetSize.height / bounds.height

    const nextXRaw = state.lastX + deltaX * scaleX
    const nextYRaw = state.lastY + (-deltaY) * scaleY
    const nextX = Math.min(Math.max(nextXRaw, 0), targetSize.width)
    const nextY = Math.min(Math.max(nextYRaw, 0), targetSize.height)
    const nextXRatio = targetSize.width === 0 ? 0 : nextX / targetSize.width
    const nextYRatio = targetSize.height === 0 ? 0 : nextY / targetSize.height

    const now = Date.now()
    const elapsed = Math.max(8, now - state.lastSentTime)

    state.lastX = nextX
    state.lastY = nextY
    state.lastSentX = nextX
    state.lastSentY = nextY
    state.lastSentTime = now

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'MOVE',
      pointerId,
      pointerType: 'wheel',
      x: nextX,
      y: nextY,
      normalizedX: nextXRatio,
      normalizedY: nextYRatio,
      durationMs: elapsed
    })

    // Auto-release scroll pointer after timeout
    const existingTimeout = (state as any).scrollTimeout
    if (existingTimeout) {
      clearTimeout(existingTimeout)
    }
    
    (state as any).scrollTimeout = setTimeout(() => {
      const scrollState = pointerStatesRef.current.get(pointerId)
      if (scrollState) {
        const x = scrollState.lastX
        const y = scrollState.lastY
        const xRatio = x / targetSize.width
        const yRatio = y / targetSize.height
        
        dispatchPointerCommand({
          type: 'POINTER',
          action: 'UP',
          pointerId,
          pointerType: 'wheel',
          x,
          y,
          normalizedX: xRatio,
          normalizedY: yRatio,
          durationMs: 8
        })
        
        pointerStatesRef.current.delete(pointerId)
      }
    }, SCROLL_RELEASE_TIMEOUT_MS)
  }, [isStreamReady, selectedDeviceId, baseSize, physicalSize, dispatchPointerCommand])

  function closeDeviceModal() {
    setIsModalOpen(false)
    setRemoteStream(null)
    setSelectedDeviceId(null)
    pendingRequestRef.current = null
    setIsStreamReady(false)
    setVideoSize(null)
    clearAllPointerStates()
  }

  return (
    <Layout style={{ background: '#fff', minHeight: '100vh' }}>
      <Layout.Header style={{ background: '#fff', display: 'flex', justifyContent: 'center', height: 'auto', lineHeight: 'normal', padding: '12px 16px' }}>
        <Space direction="vertical" align="center">
          <Space>
            <Text strong type="secondary">WebSocket</Text>
            <Text code>{wsUrl}</Text>
          </Space>
          <Space>
            <Tag color={isConnected ? 'green' : 'red'}>{isConnected ? 'Connected' : 'Disconnected'}</Tag>
            <Tag color="blue">Devices: {connectedDevices}</Tag>
          </Space>
        </Space>
      </Layout.Header>
      <Layout.Content style={{ padding: 16, background: '#fff' }}>
        {error && (
          <Alert message="Connection Error" description={error} type="error" showIcon />
        )}

        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Row gutter={[16, 16]}>
            {devices.filter(d => d.isConnected).map(device => (
              <Col xs={24} sm={12} md={8} lg={6} key={device.id}>
                <Card
                  title={device.name || device.id}
                  hoverable
                  onClick={() => openDeviceModal(device.id)}
                >
                  <div
                    style={{
                      width: '100%',
                      height: 180,
                      border: '2px solid #52c41a',
                      borderRadius: 8,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      background: 'rgba(82,196,26,0.06)'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                      <CheckCircleTwoTone twoToneColor="#52c41a" style={{ fontSize: 28 }} />
                      <Text strong style={{ color: '#52c41a', marginLeft: 8 }}>Đã kết nối</Text>
                    </div>
                  </div>
                </Card>
              </Col>
            ))}
          </Row>
        </Space>

        <Modal
          centered
          open={isModalOpen}
          onCancel={closeDeviceModal}
          footer={null}
          title={selectedDeviceId ? `${devices.find(d => d.id === selectedDeviceId)?.name || selectedDeviceId}` : 'Device'}
          width={'fit-content'}
          styles={{ body: { padding: 0 } }}
          afterOpenChange={(open) => {
            if (open) {
              const sid = selectedDeviceId
              if (!sid) return
              const stream = webrtcManagerRef.current?.getRemoteStream(sid) || null
              if (videoRef.current) {
                console.log('[WEB][ModalOpen] deviceId=', sid, 'hasStream=', !!stream)
                if (stream && videoRef.current.srcObject !== stream) {
                  try { videoRef.current.pause() } catch {}
                  try {
                    // @ts-ignore
                    videoRef.current.srcObject = stream
                    if (videoRef.current.videoWidth && videoRef.current.videoHeight) {
                      setVideoSize({ width: videoRef.current.videoWidth, height: videoRef.current.videoHeight })
                    }
                    console.log('[WEB][Video] srcObject attached')
                    videoRef.current.play()
                      .then(() => console.log('[WEB][Video] play() resolved'))
                      .catch((e) => console.warn('[WEB][Video] play() rejected', e))
                  } catch (e) {
                    console.error('[WEB][Video] attach/play error', e)
                  }
                } else if (!stream) {
                  console.warn('[WEB][ModalOpen] No remoteStream for device, will retry attach')
                  let tries = 0
                  const tick = () => {
                    if (!videoRef.current) return
                    const s = webrtcManagerRef.current?.getRemoteStream(sid) || null
                    if (s) {
                      try {
                        // @ts-ignore
                        videoRef.current.srcObject = s
                        console.log('[WEB][Video] srcObject attached (retry)')
                        videoRef.current.play()
                          .then(() => console.log('[WEB][Video] play() resolved (retry)'))
                          .catch((e) => console.warn('[WEB][Video] play() rejected (retry)', e))
                      } catch (e) {
                        console.error('[WEB][Video] attach/play error (retry)', e)
                      }
                      attachRetryRef.current && window.clearInterval(attachRetryRef.current)
                      attachRetryRef.current = null
                      return
                    }
                    tries++
                    if (tries >= 10) {
                      console.warn('[WEB][Retry] remoteStream not available after retries')
                      attachRetryRef.current && window.clearInterval(attachRetryRef.current)
                      attachRetryRef.current = null
                    }
                  }
                  attachRetryRef.current = window.setInterval(tick, 300)
                } else {
                  console.log('[WEB][ModalOpen] Stream already attached, skip re-attach')
                }
              }
            }
            if (!open && attachRetryRef.current) {
              window.clearInterval(attachRetryRef.current)
              attachRetryRef.current = null
            }
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'center', position: 'relative' }}>
            <div
              style={videoContainerStyle}
              ref={videoContainerRef}
              tabIndex={0}
              onMouseDown={handleMouseDown}
              onMouseMove={handleMouseMove}
              onMouseUp={handleMouseUp}
              onMouseLeave={handleMouseUp}
              onWheel={handleWheel}
            >
              {!isStreamReady && (
                <div style={{ 
                  color: '#fff', 
                  display: 'flex', 
                  flexDirection: 'column', 
                  alignItems: 'center',
                  gap: '12px'
                }}>
                  <Spin size="large" />
                  <div style={{ 
                    fontSize: '14px', 
                    textAlign: 'center',
                    maxWidth: '200px',
                    lineHeight: '1.4'
                  }}>
                    {isOrientationChanging ? '🔄 Đang xoay màn hình...' : 
                     physicalSize ? 'Đang cập nhật màn hình...' : 'Đang chờ thiết bị...'}
                  </div>
                </div>
              )}
              <video
                key={videoKey}
                ref={videoRef}
                autoPlay
                playsInline
                muted
                style={{
                  display: isStreamReady ? 'block' : 'none',
                  width: '100%',
                  height: '100%',
                  objectFit: 'cover', // Fill toàn bộ container thay vì contain
                  borderRadius: '12px' // Bo tròn video element để match với container
                }}
                onLoadedMetadata={() => {
                  console.log('[WEB][Video] loadedmetadata, readyState=', videoRef.current?.readyState)
                  if (videoRef.current?.videoWidth && videoRef.current?.videoHeight) {
                    setVideoSize({ width: videoRef.current.videoWidth, height: videoRef.current.videoHeight })
                  }
                }}
                onPlay={() => {
                  console.log('[WEB][Video] onPlay, readyState=', videoRef.current?.readyState)
                  if (videoRef.current?.videoWidth && videoRef.current?.videoHeight) {
                    setVideoSize({ width: videoRef.current.videoWidth, height: videoRef.current.videoHeight })
                  }
                  setIsStreamReady(true)
                }}
                onPause={() => console.log('[WEB][Video] onPause')}
                onError={(e) => console.error('[WEB][Video] onError', e)}
              />
            </div>
          </div>
        </Modal>
      </Layout.Content>
    </Layout>
  )
}

export default App
