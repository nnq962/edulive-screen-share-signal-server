import { Typography, Alert, Space, Row, Col, Card, Modal, Tag, Layout, Spin } from 'antd'
import { CheckCircleTwoTone } from '@ant-design/icons'
import { useWebSocket } from './hooks/useWebSocket'
import { WebRTCManager } from './utils/webrtc'
import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react'

const { Text } = Typography

type PointerAction = 'DOWN' | 'MOVE' | 'UP' | 'CANCEL'

type KeyboardCommand = {
  type: 'KEYBOARD'
  action: string
  text?: string
  key?: string
  code?: string
  keyCode?: number
  altKey?: boolean
  ctrlKey?: boolean
  shiftKey?: boolean
  metaKey?: boolean
}

interface PointerState {
  pointerId: number
  pointerType: string
  startX: number
  startY: number
  startRatioX: number
  startRatioY: number
  lastX: number
  lastY: number
  lastRatioX: number
  lastRatioY: number
  lastSentX: number
  lastSentY: number
  lastSentTime: number
  lastSentPerfTime: number
  lastPressure: number
  lastSentPressure: number
  lastWidth: number
  lastHeight: number
  startTime: number
  keepAliveTimer: number | null
  releaseTimer: number | null
}

const POINTER_KEEP_ALIVE_INTERVAL_MS = 40
const POINTER_KEEP_ALIVE_MIN_ELAPSED_MS = 55
const SCROLL_POINTER_ID = -1
const SCROLL_RELEASE_TIMEOUT_MS = 140

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
  const videoContainerRef = useRef<HTMLDivElement>(null)
  const pointerStatesRef = useRef<Map<number, PointerState>>(new Map())

  const stopPointerTimer = useCallback((state: PointerState | undefined | null) => {
    if (!state) return
    if (state.keepAliveTimer != null) {
      window.clearInterval(state.keepAliveTimer)
      state.keepAliveTimer = null
    }
    if (state.releaseTimer != null) {
      window.clearTimeout(state.releaseTimer)
      state.releaseTimer = null
    }
  }, [])

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

      // Override onRemoteStream to update state
      webrtcManagerRef.current.onRemoteStream = (deviceId: string, stream: MediaStream) => {
        // Chỉ cập nhật stream khi trùng với thiết bị đang được chọn
        if (deviceId === selectedDeviceId) {
          setRemoteStream(stream)
        }
      }

      // Forward ICE candidates from viewer (web) back to server → device
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

  // Update video element when remote stream changes
  useEffect(() => {
    if (videoRef.current && remoteStream) {
      videoRef.current.srcObject = remoteStream
      const play = async () => {
        try {
          await videoRef.current?.play()
        } catch (e) {
          console.warn('Tự phát video bị chặn, sẽ phát khi người dùng tương tác', e)
        }
      }
      play()
    }
  }, [remoteStream])

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
    if (!isConnected) {
      return
    }
    const pendingId = pendingRequestRef.current
    if (!pendingId) {
      return
    }
    const device = devices.find(d => d.id === pendingId && d.isConnected)
    if (!device) {
      return
    }
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

    // Handle WebRTC signaling messages
    switch (message.type) {
      case 'DEVICE_WENT_OFFLINE': {
        // Close modal if the selected device went offline or stopped streaming
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
        if (selectedDeviceId && message.deviceId === selectedDeviceId && message.data) {
          setPhysicalSize({ width: message.data.width, height: message.data.height })
        }
        break
    }
  }

  async function handleOffer(deviceId: string, offer: any) {
    try {
      if (!webrtcManagerRef.current) return

      console.log('📥 Handling OFFER from device:', deviceId)

      // Parse offer string to RTCSessionDescriptionInit
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

      // Send answer back to device
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

      // Parse answer string to RTCSessionDescriptionInit
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

      // Parse candidate string to RTCIceCandidateInit
      let candidateObj: RTCIceCandidateInit

      if (typeof candidate === 'string') {
        // If candidate is a JSON string, parse it
        try {
          const parsed = JSON.parse(candidate)
          const cand = parsed.candidate ?? parsed.sdp ?? candidate
          candidateObj = {
            candidate: cand,
            sdpMLineIndex: parsed.sdpMLineIndex ?? parsed.mlineindex ?? parsed.mLineIndex,
            sdpMid: parsed.sdpMid ?? parsed.mid
          }
        } catch {
          // If parsing fails, treat as direct SDP/candidate string
          candidateObj = {
            candidate: candidate
          }
        }
      } else {
        // If candidate is already an object
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
    // Try to get an existing stream immediately
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

  const baseSize = videoSize ?? physicalSize

  const renderedVideoSize = useMemo(() => {
    if (!baseSize) return null
    const maxWidth = window.innerWidth * 0.9
    const maxHeight = window.innerHeight * 0.8
    const scale = Math.min(maxWidth / baseSize.width, maxHeight / baseSize.height, 1)
    return {
      width: Math.round(baseSize.width * scale),
      height: Math.round(baseSize.height * scale)
    }
  }, [baseSize])

  const displayWidth = renderedVideoSize?.width ?? baseSize?.width ?? 320
  const displayHeight = renderedVideoSize?.height ?? baseSize?.height ?? 568

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
    pressure?: number
    width?: number
    height?: number
  }) => {
    if (!selectedDeviceId) return
    try {
      sendMessage({
        type: 'CONTROL_COMMAND',
        deviceId: selectedDeviceId,
        data: command
      } as any)
    } catch (error) {
      console.error('[WEB][Control] Failed to send pointer command', error)
    }
  }, [selectedDeviceId, sendMessage])

  const finalizePointer = useCallback((pointerId: number, action: PointerAction = 'UP') => {
    const state = pointerStatesRef.current.get(pointerId)
    if (!state) return

    const perfNow = performance.now()
    const elapsed = Math.max(8, Math.round(perfNow - state.lastSentPerfTime))
    const timestamp = state.lastSentTime + elapsed
    state.lastSentTime = timestamp
    state.lastSentPerfTime = perfNow

    dispatchPointerCommand({
      type: 'POINTER',
      action,
      pointerId,
      pointerType: state.pointerType,
      x: state.lastX,
      y: state.lastY,
      normalizedX: state.lastRatioX,
      normalizedY: state.lastRatioY,
      durationMs: elapsed,
      pressure: state.lastPressure,
      width: state.lastWidth,
      height: state.lastHeight
    })

    console.log('[WEB][Control] POINTER', action, {
      pointerId,
      x: state.lastX,
      y: state.lastY,
      pointerType: state.pointerType
    })

    stopPointerTimer(state)
    pointerStatesRef.current.delete(pointerId)
  }, [dispatchPointerCommand, stopPointerTimer])

  const schedulePointerRelease = useCallback((pointerId: number, timeout: number = SCROLL_RELEASE_TIMEOUT_MS) => {
    const state = pointerStatesRef.current.get(pointerId)
    if (!state) return
    if (state.releaseTimer != null) {
      window.clearTimeout(state.releaseTimer)
    }
    state.releaseTimer = window.setTimeout(() => {
      const current = pointerStatesRef.current.get(pointerId)
      if (!current) {
        return
      }
      current.releaseTimer = null
      finalizePointer(pointerId, 'UP')
    }, timeout)
  }, [finalizePointer])

  const clearAllPointerStates = useCallback(() => {
    const ids = Array.from(pointerStatesRef.current.keys())
    ids.forEach(id => finalizePointer(id, 'CANCEL'))
  }, [finalizePointer])

  const dispatchKeyboardCommand = useCallback((command: KeyboardCommand) => {
    if (!selectedDeviceId) return
    try {
      sendMessage({
        type: 'CONTROL_COMMAND',
        deviceId: selectedDeviceId,
        data: command
      } as any)
    } catch (error) {
      console.error('[WEB][Control] Failed to send keyboard command', error)
    }
  }, [selectedDeviceId, sendMessage])

  useEffect(() => {
    if (!isStreamReady) {
      clearAllPointerStates()
    }
  }, [isStreamReady, clearAllPointerStates])

  useEffect(() => () => clearAllPointerStates(), [clearAllPointerStates])

  const shouldCaptureKeyboard = isModalOpen && isStreamReady && !!selectedDeviceId

  const handleKeyDown = useCallback((event: KeyboardEvent) => {
    if (!shouldCaptureKeyboard) return

    const target = event.target as HTMLElement | null
    if (target) {
      const tagName = target.tagName
      if (tagName === 'INPUT' || tagName === 'TEXTAREA' || target.isContentEditable) {
        return
      }
    }

    const key = event.key
    const printable = key.length === 1 && !event.ctrlKey && !event.metaKey

    const modifiers = {
      altKey: event.altKey,
      ctrlKey: event.ctrlKey,
      shiftKey: event.shiftKey,
      metaKey: event.metaKey
    }

    const baseCommand = {
      type: 'KEYBOARD' as const,
      key,
      code: event.code,
      keyCode: event.keyCode,
      ...modifiers
    }

    if (event.ctrlKey || event.metaKey) {
      if (event.key.toLowerCase() !== 'v') {
        return
      }
    }

    if (key === 'Backspace') {
      event.preventDefault()
      dispatchKeyboardCommand({
        ...baseCommand,
        action: 'BACKSPACE'
      })
      return
    }

    if (key === 'Enter') {
      event.preventDefault()
      dispatchKeyboardCommand({
        ...baseCommand,
        action: 'ENTER'
      })
      return
    }

    if (key === 'Tab') {
      event.preventDefault()
      dispatchKeyboardCommand({
        ...baseCommand,
        action: 'TAB'
      })
      return
    }

    if (printable) {
      event.preventDefault()
      dispatchKeyboardCommand({
        ...baseCommand,
        action: 'INSERT_TEXT',
        text: key
      })
      return
    }
  }, [dispatchKeyboardCommand, shouldCaptureKeyboard])

  const handlePasteEvent = useCallback((event: ClipboardEvent) => {
    if (!shouldCaptureKeyboard) return

    const target = event.target as HTMLElement | null
    if (target) {
      const tagName = target.tagName
      if (tagName === 'INPUT' || tagName === 'TEXTAREA' || target.isContentEditable) {
        return
      }
    }

    const text = event.clipboardData?.getData('text')
    if (!text) {
      return
    }

    event.preventDefault()
    dispatchKeyboardCommand({
      type: 'KEYBOARD',
      action: 'INSERT_TEXT',
      text,
      key: 'Paste',
      code: 'Paste'
    })
  }, [dispatchKeyboardCommand, shouldCaptureKeyboard])

  useEffect(() => {
    if (!shouldCaptureKeyboard) return

    const keyListener = (event: KeyboardEvent) => handleKeyDown(event)
    const pasteListener = (event: ClipboardEvent) => handlePasteEvent(event)

    window.addEventListener('keydown', keyListener, true)
    window.addEventListener('paste', pasteListener, true)

    return () => {
      window.removeEventListener('keydown', keyListener, true)
      window.removeEventListener('paste', pasteListener, true)
    }
  }, [handleKeyDown, handlePasteEvent, shouldCaptureKeyboard])

  useEffect(() => {
    if (isModalOpen && isStreamReady) {
      try {
        videoContainerRef.current?.focus()
      } catch (error) {
        console.warn('[WEB][Control] Unable to focus video container', error)
      }
    }
  }, [isModalOpen, isStreamReady])

  const handlePointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (!isStreamReady || !selectedDeviceId || !baseSize || !videoContainerRef.current) return
    if (event.pointerType === 'mouse' && event.button !== 0) return

    const bounds = videoContainerRef.current.getBoundingClientRect()
    if (bounds.width === 0 || bounds.height === 0) return

    const pointerId = event.pointerId
    const perfNow = performance.now()
    const existingState = pointerStatesRef.current.get(pointerId)
    if (existingState) {
      stopPointerTimer(existingState)
      pointerStatesRef.current.delete(pointerId)
    }
    const rawXRatio = (event.clientX - bounds.left) / bounds.width
    const rawYRatio = (event.clientY - bounds.top) / bounds.height
    const xRatio = Math.min(Math.max(rawXRatio, 0), 1)
    const yRatio = Math.min(Math.max(rawYRatio, 0), 1)
    const targetSize = physicalSize ?? baseSize
    const x = Math.round(targetSize.width * xRatio)
    const y = Math.round(targetSize.height * yRatio)
    const now = typeof event.timeStamp === 'number' ? event.timeStamp : performance.now()

    const pressure = event.pressure && event.pressure > 0 ? event.pressure : (event.pointerType === 'mouse' ? 0 : 0.5)
    const width = event.width || 0
    const height = event.height || 0
    const pointerType = event.pointerType || 'touch'

    const pointerState: PointerState = {
      pointerId,
      pointerType,
      startX: x,
      startY: y,
      startRatioX: xRatio,
      startRatioY: yRatio,
      lastX: x,
      lastY: y,
      lastRatioX: xRatio,
      lastRatioY: yRatio,
      lastSentX: x,
      lastSentY: y,
      lastSentTime: now,
      lastSentPerfTime: perfNow,
      lastPressure: pressure,
      lastSentPressure: pressure,
      lastWidth: width,
      lastHeight: height,
      startTime: now,
      keepAliveTimer: null,
      releaseTimer: null
    }
    pointerStatesRef.current.set(pointerId, pointerState)

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'DOWN',
      pointerId,
      pointerType,
      x,
      y,
      normalizedX: xRatio,
      normalizedY: yRatio,
      durationMs: 16,
      pressure,
      width,
      height
    })

    console.log('[WEB][Control] POINTER DOWN', {
      pointerId,
      pointerType,
      x,
      y,
      pressure,
      base: targetSize
    })

    const keepAliveTimer = window.setInterval(() => {
      const current = pointerStatesRef.current.get(pointerId)
      if (!current) {
        window.clearInterval(keepAliveTimer)
        return
      }
      const perfNowInner = performance.now()
      const elapsed = perfNowInner - current.lastSentPerfTime
      if (elapsed < POINTER_KEEP_ALIVE_MIN_ELAPSED_MS) {
        return
      }

      const approxTimestamp = current.lastSentTime + elapsed
      current.lastSentTime = approxTimestamp
      current.lastSentPerfTime = perfNowInner
      dispatchPointerCommand({
        type: 'POINTER',
        action: 'MOVE',
        pointerId,
        pointerType: current.pointerType,
        x: current.lastX,
        y: current.lastY,
        normalizedX: current.lastRatioX,
        normalizedY: current.lastRatioY,
        durationMs: Math.max(8, Math.round(elapsed)),
        pressure: current.lastPressure,
        width: current.lastWidth,
        height: current.lastHeight
      })
    }, POINTER_KEEP_ALIVE_INTERVAL_MS)
    pointerState.keepAliveTimer = keepAliveTimer

    try {
      (event.currentTarget as HTMLElement).setPointerCapture(pointerId)
    } catch (error) {
      console.warn('[WEB][Control] Failed to set pointer capture', error)
    }
    event.preventDefault()
  }, [isStreamReady, selectedDeviceId, baseSize, physicalSize, dispatchPointerCommand, stopPointerTimer])

  const handlePointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const state = pointerStatesRef.current.get(event.pointerId)
    if (!state || !videoContainerRef.current || !baseSize) return

    const bounds = videoContainerRef.current.getBoundingClientRect()
    if (bounds.width === 0 || bounds.height === 0) return

    const targetSize = physicalSize ?? baseSize
    const targetDiagonal = Math.hypot(targetSize.width, targetSize.height)

    const processSample = (
      clientX: number,
      clientY: number,
      pressureSample: number | undefined,
      widthSample: number | undefined,
      heightSample: number | undefined,
      sampleTime: number | undefined
    ) => {
      const rawXRatio = (clientX - bounds.left) / bounds.width
      const rawYRatio = (clientY - bounds.top) / bounds.height
      const xRatio = Math.min(Math.max(rawXRatio, 0), 1)
      const yRatio = Math.min(Math.max(rawYRatio, 0), 1)
      const x = Math.round(targetSize.width * xRatio)
      const y = Math.round(targetSize.height * yRatio)
      const pressure = pressureSample && pressureSample > 0 ? pressureSample : state.lastPressure
      const width = widthSample || state.lastWidth
      const height = heightSample || state.lastHeight

      state.lastX = x
      state.lastY = y
      state.lastRatioX = xRatio
      state.lastRatioY = yRatio
      state.lastPressure = pressure
      state.lastWidth = width
      state.lastHeight = height

      const timestamp = typeof sampleTime === 'number' ? sampleTime : performance.now()
      const elapsed = Math.max(0, timestamp - state.lastSentTime)
      const delta = Math.hypot(x - state.lastSentX, y - state.lastSentY)
      const minDistance = Math.max(1.5, targetDiagonal * 0.0015)
      const minInterval = 8

      if (delta < minDistance && elapsed < minInterval) {
        return false
      }

      state.lastSentX = x
      state.lastSentY = y
      state.lastSentTime = timestamp
      state.lastSentPressure = pressure
      state.lastSentPerfTime = performance.now()

      dispatchPointerCommand({
        type: 'POINTER',
        action: 'MOVE',
        pointerId: event.pointerId,
        pointerType: state.pointerType,
        x,
        y,
        normalizedX: xRatio,
        normalizedY: yRatio,
        durationMs: Math.max(8, Math.round(elapsed)),
        pressure,
        width,
        height
      })
      return true
    }

    const nativeEvent = event.nativeEvent
    const coalesced =
      typeof nativeEvent.getCoalescedEvents === 'function' ? nativeEvent.getCoalescedEvents() : []
    const samples = coalesced && coalesced.length > 0 ? [...coalesced, nativeEvent] : [nativeEvent]
    let dispatched = false

    for (const sample of samples) {
      dispatched = processSample(
        sample.clientX,
        sample.clientY,
        sample.pressure,
        sample.width,
        sample.height,
        sample.timeStamp
      ) || dispatched
    }

    if (dispatched) {
      event.preventDefault()
    }
  }, [baseSize, physicalSize, dispatchPointerCommand])

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

    const targetSize = physicalSize ?? baseSize
    const pointerId = SCROLL_POINTER_ID
    let state = pointerStatesRef.current.get(pointerId)

    const basePressure = 0.45
    const pointerType = 'wheel'

    const resolvePosition = (clientX: number, clientY: number) => {
      const xRatioRaw = (clientX - bounds.left) / bounds.width
      const yRatioRaw = (clientY - bounds.top) / bounds.height
      const xRatio = Math.min(Math.max(xRatioRaw, 0), 1)
      const yRatio = Math.min(Math.max(yRatioRaw, 0), 1)
      return {
        xRatio,
        yRatio,
        x: Math.round(targetSize.width * xRatio),
        y: Math.round(targetSize.height * yRatio)
      }
    }

    if (!state) {
      const start = resolvePosition(event.clientX, event.clientY)
      const now = typeof event.timeStamp === 'number' ? event.timeStamp : performance.now()
      const perfNow = performance.now()
      state = {
        pointerId,
        pointerType,
        startX: start.x,
        startY: start.y,
        startRatioX: start.xRatio,
        startRatioY: start.yRatio,
        lastX: start.x,
        lastY: start.y,
        lastRatioX: start.xRatio,
        lastRatioY: start.yRatio,
        lastSentX: start.x,
        lastSentY: start.y,
        lastSentTime: now,
        lastSentPerfTime: perfNow,
        lastPressure: basePressure,
        lastSentPressure: basePressure,
        lastWidth: 0,
        lastHeight: 0,
        startTime: now,
        keepAliveTimer: null,
        releaseTimer: null
      }
      pointerStatesRef.current.set(pointerId, state)

      dispatchPointerCommand({
        type: 'POINTER',
        action: 'DOWN',
        pointerId,
        pointerType,
        x: start.x,
        y: start.y,
        normalizedX: start.xRatio,
        normalizedY: start.yRatio,
        durationMs: 16,
        pressure: basePressure,
        width: 0,
        height: 0
      })

      console.log('[WEB][Control] POINTER DOWN (wheel)', {
        pointerId,
        x: start.x,
        y: start.y
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

    const timestamp = typeof event.timeStamp === 'number' ? event.timeStamp : performance.now()
    const elapsed = Math.max(8, Math.round(timestamp - state.lastSentTime))

    state.lastX = nextX
    state.lastY = nextY
    state.lastRatioX = nextXRatio
    state.lastRatioY = nextYRatio
    state.lastPressure = basePressure
    state.lastWidth = 0
    state.lastHeight = 0
    state.lastSentX = nextX
    state.lastSentY = nextY
    state.lastSentTime = timestamp
    state.lastSentPerfTime = performance.now()
    state.lastSentPressure = basePressure

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'MOVE',
      pointerId,
      pointerType,
      x: nextX,
      y: nextY,
      normalizedX: nextXRatio,
      normalizedY: nextYRatio,
      durationMs: elapsed,
      pressure: basePressure,
      width: 0,
      height: 0
    })

    schedulePointerRelease(pointerId)
  }, [isStreamReady, selectedDeviceId, baseSize, physicalSize, dispatchPointerCommand, schedulePointerRelease])

  const handlePointerEnd = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const state = pointerStatesRef.current.get(event.pointerId)
    if (!state) return

    try {
      (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
    } catch {}

    const x = state.lastX
    const y = state.lastY
    const xRatio = state.lastRatioX
    const yRatio = state.lastRatioY
    const now = typeof event.timeStamp === 'number' ? event.timeStamp : performance.now()
    const duration = Math.max(8, Math.round(now - state.lastSentTime))
    const pressure = event.pressure && event.pressure > 0 ? event.pressure : state.lastPressure

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'UP',
      pointerId: event.pointerId,
      pointerType: state.pointerType,
      x,
      y,
      normalizedX: xRatio,
      normalizedY: yRatio,
      durationMs: duration,
      pressure,
      width: state.lastWidth,
      height: state.lastHeight
    })

    console.log('[WEB][Control] POINTER UP', {
      pointerId: state.pointerId,
      x,
      y,
      duration,
      pointerType: state.pointerType
    })

    stopPointerTimer(state)
    pointerStatesRef.current.delete(event.pointerId)
    event.preventDefault()
  }, [dispatchPointerCommand, stopPointerTimer])

  const handlePointerCancel = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const state = pointerStatesRef.current.get(event.pointerId)
    if (!state) return
    try {
      (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId)
    } catch {}

    dispatchPointerCommand({
      type: 'POINTER',
      action: 'CANCEL',
      pointerId: event.pointerId,
      pointerType: state.pointerType,
      x: state.lastSentX,
      y: state.lastSentY,
      normalizedX: state.lastRatioX,
      normalizedY: state.lastRatioY,
      durationMs: 8,
      pressure: state.lastSentPressure,
      width: state.lastWidth,
      height: state.lastHeight
    })

    console.log('[WEB][Control] POINTER CANCEL', {
      pointerId: state.pointerId,
      x: state.lastSentX,
      y: state.lastSentY,
      pointerType: state.pointerType
    })

    stopPointerTimer(state)
    pointerStatesRef.current.delete(event.pointerId)
    event.preventDefault()
  }, [dispatchPointerCommand, stopPointerTimer])

  function closeDeviceModal() {
    setIsModalOpen(false)
    setRemoteStream(null)
    setSelectedDeviceId(null)
    pendingRequestRef.current = null
    setIsStreamReady(false)
    setVideoSize(null)
    clearAllPointerStates()
  }

  // Web không còn quyền Disconnect. Việc ngắt/kết nối do Android quản lý.

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
                    // Attach and play
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
                  // Retry attach up to ~3s
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
            // Cleanup retries when modal closes
            if (!open && attachRetryRef.current) {
              window.clearInterval(attachRetryRef.current)
              attachRetryRef.current = null
            }
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'center', position: 'relative' }}>
            <div
              style={{
                background: '#000',
                position: 'relative',
                overflow: 'hidden',
                minWidth: displayWidth,
                minHeight: displayHeight,
                width: displayWidth,
                height: displayHeight,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                cursor: isStreamReady ? 'pointer' : 'default',
                touchAction: 'none',
                outline: 'none'
              }}
              ref={videoContainerRef}
              tabIndex={0}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={handlePointerEnd}
              onPointerCancel={handlePointerCancel}
              onPointerLeave={handlePointerCancel}
              onLostPointerCapture={handlePointerCancel}
              onWheel={handleWheel}
            >
              {!isStreamReady && (
                <div style={{ color: '#fff' }}>
                  <Spin tip="Đang chờ thiết bị..." size="large" />
                </div>
              )}
              <video
                ref={videoRef}
                autoPlay
                playsInline
                muted
                style={{
                  display: isStreamReady ? 'block' : 'none',
                  width: '100%',
                  height: '100%',
                  objectFit: 'contain'
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
