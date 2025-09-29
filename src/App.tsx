import { Typography, Alert, Space, Row, Col, Card, Modal, Tag, Layout, Spin } from 'antd'
import { CheckCircleTwoTone } from '@ant-design/icons'
import { useWebSocket } from './hooks/useWebSocket'
import { WebRTCManager } from './utils/webrtc'
import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react'

const { Text } = Typography

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

  function closeDeviceModal() {
    setIsModalOpen(false)
    setRemoteStream(null)
    setSelectedDeviceId(null)
    pendingRequestRef.current = null
    setIsStreamReady(false)
    setVideoSize(null)
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
        }
      }
    }
  }, [remoteStream])

  const renderedVideoSize = useMemo(() => {
    if (!videoSize) return null
    const maxWidth = window.innerWidth * 0.9
    const maxHeight = window.innerHeight * 0.8
    const scale = Math.min(maxWidth / videoSize.width, maxHeight / videoSize.height, 1)
    return {
      width: Math.round(videoSize.width * scale),
      height: Math.round(videoSize.height * scale)
    }
  }, [videoSize])

  const displayWidth = renderedVideoSize?.width ?? videoSize?.width ?? 320
  const displayHeight = renderedVideoSize?.height ?? videoSize?.height ?? 568

  const handleVideoClick = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    if (!selectedDeviceId || !videoSize) return
    const bounds = (event.currentTarget as HTMLDivElement).getBoundingClientRect()
    const xRatio = (event.clientX - bounds.left) / bounds.width
    const yRatio = (event.clientY - bounds.top) / bounds.height
    const x = Math.round(videoSize.width * xRatio)
    const y = Math.round(videoSize.height * yRatio)
    try {
      sendMessage({
        type: 'CONTROL_COMMAND',
        deviceId: selectedDeviceId,
        data: {
          type: 'TAP',
          x,
          y,
          durationMs: 100
        }
      } as any)
      console.log('[WEB][Control] TAP sent to', selectedDeviceId, x, y)
    } catch (error) {
      console.error('[WEB][Control] Failed to send tap', error)
    }
  }, [selectedDeviceId, videoSize, sendMessage])

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
                cursor: isStreamReady ? 'pointer' : 'default'
              }}
              onClick={isStreamReady ? handleVideoClick : undefined}
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
