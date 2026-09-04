<template>
  <div class="whep-player">
    <div class="video-frame">
      <video ref="videoRef" autoplay muted playsinline :loop="fallbackActive"></video>
      <div v-if="status !== 'playing'" class="video-overlay">
        <span v-if="status === 'connecting'" class="spinner">⟳</span>
        <span>{{ statusText }}</span>
      </div>
      <span class="live-badge" :class="status">{{ status === 'playing' ? 'LIVE' : status === 'fallback' ? 'DEMO' : statusText }}</span>
    </div>
    <div v-if="showControls" class="video-controls">
      <button type="button" :disabled="status === 'connecting'" @click="start">连接</button>
      <button type="button" @click="stop">断开</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { tokenStore } from '../api/auth'
import { mediaAuthHeaders, mediaUrl } from '../config/runtime'

const props = defineProps({
  streamUrl: { type: String, default: '' },
  fallbackPath: { type: String, default: '' },
  fallbackVideo: { type: String, default: '' },
  autoStart: { type: Boolean, default: true },
  showControls: { type: Boolean, default: false },
  reconnectDelay: { type: Number, default: 4000 }
})
const emit = defineEmits(['status'])
const videoRef = ref(null)
const status = ref('idle')
const fallbackActive = ref(false)
let peer = null
let sessionUrl = ''
let reconnectTimer = null
let manuallyStopped = false

const resolvedUrl = computed(() => props.streamUrl || (props.fallbackPath ? mediaUrl(props.fallbackPath) : ''))
const statusText = computed(() => ({
  idle: '未连接',
  connecting: '正在连接',
  playing: '直播中',
  fallback: '演示视频',
  error: '流不可用'
}[status.value] || '未连接'))

function setStatus(value) {
  status.value = value
  emit('status', value)
}

function cleanupPeer(deleteSession = true) {
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (peer) {
    peer.ontrack = null
    peer.onconnectionstatechange = null
    peer.close()
    peer = null
  }
  if (deleteSession && sessionUrl) {
    fetch(sessionUrl, {
      method: 'DELETE',
      headers: mediaAuthHeaders(tokenStore.getAccess())
    }).catch(() => {})
  }
  sessionUrl = ''
  if (videoRef.value?.srcObject) {
    videoRef.value.srcObject.getTracks?.().forEach(track => track.stop())
    videoRef.value.srcObject = null
  }
  if (videoRef.value && !fallbackActive.value) videoRef.value.removeAttribute('src')
}

function playFallback() {
  if (!props.fallbackVideo || !videoRef.value) return false
  fallbackActive.value = true
  videoRef.value.srcObject = null
  videoRef.value.src = props.fallbackVideo
  videoRef.value.loop = true
  videoRef.value.play().catch(() => {})
  setStatus('fallback')
  return true
}

function scheduleReconnect() {
  if (manuallyStopped || !props.autoStart || reconnectTimer) return
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    start()
  }, Math.max(1000, props.reconnectDelay))
}

function waitForIceGatheringComplete(pc) {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise(resolve => {
    const timeout = window.setTimeout(done, 2200)
    function done() {
      window.clearTimeout(timeout)
      pc.removeEventListener('icegatheringstatechange', onChange)
      resolve()
    }
    function onChange() {
      if (pc.iceGatheringState === 'complete') done()
    }
    pc.addEventListener('icegatheringstatechange', onChange)
  })
}

async function start() {
  if (!resolvedUrl.value || !window.RTCPeerConnection) {
    setStatus('error')
    return
  }
  manuallyStopped = false
  fallbackActive.value = false
  cleanupPeer()
  setStatus('connecting')
  try {
    const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] })
    peer = pc
    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.ontrack = event => {
      const stream = event.streams?.[0]
      if (!stream || !videoRef.value) return
      videoRef.value.srcObject = stream
      videoRef.value.play().catch(() => {})
      setStatus('playing')
    }
    pc.onconnectionstatechange = () => {
      if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) {
        setStatus('error')
        scheduleReconnect()
      }
    }
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    await waitForIceGatheringComplete(pc)
    const response = await fetch(resolvedUrl.value, {
      method: 'POST',
      headers: mediaAuthHeaders(tokenStore.getAccess(), { 'Content-Type': 'application/sdp' }),
      body: pc.localDescription?.sdp || ''
    })
    if (!response.ok) throw new Error(`WHEP ${response.status}`)
    const location = response.headers.get('location') || response.headers.get('Location')
    if (location) sessionUrl = new URL(location, new URL(resolvedUrl.value, window.location.href)).toString()
    await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() })
  } catch (error) {
    console.warn('[WhepVideoPlayer] WHEP 连接失败，切换 MP4 演示视频', error)
    cleanupPeer()
    if (!playFallback()) {
      setStatus('error')
      scheduleReconnect()
    }
  }
}

function stop() {
  manuallyStopped = true
  fallbackActive.value = false
  cleanupPeer()
  setStatus('idle')
}

watch(resolvedUrl, () => {
  if (props.autoStart) start()
})

onMounted(() => {
  if (props.autoStart) start()
})
onUnmounted(() => {
  manuallyStopped = true
  cleanupPeer()
})

defineExpose({ start, stop, status })
</script>

<style scoped>
.whep-player { min-width: 0; }
.video-frame {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border: 1px solid rgba(74, 196, 255, .28);
  border-radius: 8px;
  background: #020b14;
}
video { width: 100%; height: 100%; display: block; object-fit: cover; }
.video-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: #9ab4c7;
  background: linear-gradient(135deg, rgba(2, 13, 24, .88), rgba(10, 35, 55, .72));
  font-size: 12px;
}
.spinner { animation: spin 1s linear infinite; color: #45d9ff; font-size: 17px; }
.live-badge {
  position: absolute;
  top: 7px;
  right: 7px;
  padding: 2px 7px;
  border-radius: 10px;
  color: #b4c8d5;
  background: rgba(18, 37, 53, .84);
  font-size: 9px;
  letter-spacing: .5px;
}
.live-badge.playing { color: #d8fff2; background: rgba(20, 139, 100, .82); }
.live-badge.fallback { color: #e9e4ff; background: rgba(107, 76, 210, .84); }
.live-badge.error { color: #ffe0dd; background: rgba(173, 58, 53, .84); }
.video-controls { display: flex; gap: 7px; margin-top: 7px; }
.video-controls button {
  padding: 3px 10px;
  border: 1px solid rgba(72, 184, 235, .35);
  border-radius: 5px;
  color: #bde8ff;
  background: rgba(12, 61, 88, .75);
  cursor: pointer;
}
.video-controls button:disabled { opacity: .55; cursor: wait; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
