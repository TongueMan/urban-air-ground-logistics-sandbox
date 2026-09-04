<template>
  <div v-if="!desktopReady" class="mobile-gate">
    <div class="gate-mark">翼</div>
    <h1>智巡联翼</h1>
    <p>请使用宽度不小于 1100px 的桌面设备体验三维协同巡检。</p>
  </div>

  <main v-else class="desktop-app">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">翼</span>
        <div><h1>智巡联翼</h1><p>城市车机协同巡检平台</p></div>
      </div>
      <div class="powered"><i></i> 地图与路径能力由百度地图开放平台提供</div>
      <div class="header-status">
        <span class="status-dot" :class="statusClass"></span>
        <div><small>MISSION STATUS</small><strong>{{ statusLabel }}</strong></div>
      </div>
    </header>

    <section class="workspace">
      <aside class="left-rail">
        <article class="panel mission-intro">
          <span class="eyebrow">PATROL MISSION 01</span>
          <h2>双编组低空协同巡检</h2>
          <p>巡检车沿百度规划道路机动，无人机定点放飞并执行往复式覆盖扫描，最终返航会合。</p>
          <button v-if="!hasSession" class="primary-action" :disabled="busy" @click="startMission">
            {{ busy ? '正在创建任务…' : '一键开始协同巡检' }}
          </button>
          <template v-else>
            <div class="progress-head"><span>{{ session?.queuePosition ? `排队第 ${session.queuePosition} 位` : phaseLabel(mission.missionPhase) }}</span><b>{{ number(mission.progress, 1) }}%</b></div>
            <div class="progress-track"><i :style="{ width: `${Math.min(100, Number(mission.progress || 0))}%` }"></i></div>
            <div class="mission-actions">
              <label>节奏
                <select v-model.number="timeScale" :disabled="session?.status !== 'RUNNING'" @change="updateSpeed">
                  <option v-for="value in speedOptions" :key="value" :value="value">{{ value }}×</option>
                </select>
              </label>
              <button v-if="isTerminal" @click="restartMission">重新巡检</button>
              <button v-else class="ghost-danger" @click="endMission">结束</button>
            </div>
          </template>
          <p v-if="notice" class="notice">{{ notice }}</p>
        </article>

        <article class="panel phase-panel">
          <div class="panel-title"><span>任务阶段</span><small>3 MIN LOOP</small></div>
          <ol class="phase-list">
            <li v-for="(phase, index) in phases" :key="phase.key" :class="phaseState(phase, index)">
              <span class="phase-index">0{{ index + 1 }}</span>
              <div><strong>{{ phase.label }}</strong><small>{{ phase.desc }}</small></div>
            </li>
          </ol>
        </article>

        <article class="panel fleet-panel">
          <div class="panel-title"><span>协同编组</span><small>2 TEAMS · 4 DEVICES</small></div>
          <button v-for="pair in mission.pairs || previewPairs" :key="pair.vehicleId" class="pair-card" @click="selectPair(pair)">
            <span class="pair-no">{{ pair.label?.slice(0, 2) }}</span>
            <div><strong>{{ pair.label }}</strong><small>{{ pair.vehicleId }} / {{ pair.droneId }}</small></div>
            <em>{{ pairStatus(pair) }}</em>
          </button>
        </article>
      </aside>

      <section class="map-stage panel">
        <PatrolMissionMap
          :devices="devices"
          :mission="mission"
          :selected-id="selectedId"
          :replay-rate="replayRate"
          :simulation-rate="timeScale"
          @select="selectedId = $event"
          @open-monitor="openDeviceVideo"
        />
        <div class="route-credit"><b>BD-09</b><span>车辆路线：百度路线规划结果 · 人工校核</span><span>路线版本 {{ mission.routeVersion || '1.4.0' }}</span></div>
      </section>

      <aside class="right-rail">
        <article class="panel live-panel">
          <div class="panel-title"><span>实时巡检视角</span><small>WHEP LIVE</small></div>
          <div class="video-tabs">
            <button v-for="feed in videoFeeds" :key="feed.key" :class="{ active: activeFeed === feed.key }" @click="activeFeed = feed.key">{{ feed.short }}</button>
          </div>
          <WhepVideoPlayer
            :key="currentFeed.key"
            :fallback-path="`${currentFeed.key}/whep`"
            :fallback-video="`/fallback-media/${currentFeed.file}`"
            auto-start
          />
          <div class="video-meta"><span>{{ currentFeed.label }}</span><b>{{ currentFeed.device }}</b></div>
        </article>

        <article class="panel metrics-panel">
          <div class="panel-title"><span>协同指标</span><small>REAL-TIME</small></div>
          <div class="metric-grid">
            <div><span>覆盖率</span><strong>{{ number(summary.coverage, 0) }}<i>%</i></strong></div>
            <div><span>平均电量</span><strong>{{ number(summary.battery, 0) }}<i>%</i></strong></div>
            <div><span>链路质量</span><strong>{{ number(summary.link, 0) }}<i>%</i></strong></div>
            <div><span>最大偏差</span><strong>{{ number(summary.deviation, 1) }}<i>m</i></strong></div>
          </div>
        </article>

        <article class="panel event-panel">
          <div class="panel-title"><span>任务事件</span><small>EVENT LOG</small></div>
          <div class="event-list">
            <div v-for="event in visibleEvents" :key="`${event.progress}-${event.type}`" :class="{ reached: event.reached }">
              <time>{{ String(event.progress).padStart(2, '0') }}%</time>
              <span><b>{{ event.label }}</b><small>{{ eventText(event) }}</small></span>
            </div>
          </div>
        </article>
      </aside>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import PatrolMissionMap from './components/PatrolMissionMap.vue'
import WhepVideoPlayer from './components/WhepVideoPlayer.vue'
import { changeSpeed, createSession, getCurrentSession, getMission, restartSession, sessionEventsUrl, stopSession } from './api/demo'

const EMPTY_MISSION = { state: 'STANDBY', status: 'STANDBY', progress: 0, missionPhase: 'DOCKED', pairs: [], routes: [], events: [], scanRadiusMeters: 250 }
const previewPairs = [
  { vehicleId: 'HF-VEH-000001', droneId: 'HF-UAV-000003', label: '一号协同编组' },
  { vehicleId: 'HF-VEH-000002', droneId: 'HF-UAV-000004', label: '二号协同编组' }
]
const phases = [
  { key: 'DEPART', label: '编组出发', desc: '巡检车搭载无人机沿道路机动' },
  { key: 'TAKEOFF', label: '定点放飞', desc: '到达任务点并完成空地分离' },
  { key: 'SCANNING', label: '协同扫描', desc: '无人机执行往复式覆盖巡检' },
  { key: 'RETURNING', label: '返航会合', desc: '无人机返回移动编组会合点' },
  { key: 'DOCKED', label: '入舱完成', desc: '任务归档并开放轨迹回放' }
]
const speedOptions = [0.5, 1, 2, 5]
const videoFeeds = [
  { key: 'patrol_uav_cam_01', short: '无人机 01', label: '一号编组·无人机巡检视角', device: 'HF-UAV-000003', file: 'patrol_uav_cam_01.mp4' },
  { key: 'patrol_uav_cam_02', short: '无人机 02', label: '二号编组·无人机巡检视角', device: 'HF-UAV-000004', file: 'patrol_uav_cam_02.mp4' },
  { key: 'patrol_wash_01', short: '车载视角', label: '巡检车·道路作业视角', device: 'HF-VEH-000001', file: 'patrol_wash_01.mp4' }
]

const session = ref(null)
const mission = ref({ ...EMPTY_MISSION })
const devices = ref([])
const selectedId = ref('')
const activeFeed = ref(videoFeeds[0].key)
const timeScale = ref(1)
const replayRate = ref(1)
const busy = ref(false)
const notice = ref('')
const desktopReady = ref(false)
let eventSource = null
let pollTimer = null
let desktopQuery = null

const hasSession = computed(() => Boolean(session.value?.id))
const isTerminal = computed(() => ['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(session.value?.status))
const statusClass = computed(() => String(session.value?.status || 'STANDBY').toLowerCase())
const statusLabel = computed(() => ({ STANDBY: '等待启动', QUEUED: '排队中', RUNNING: '任务执行中', COMPLETED: '巡检完成', STOPPED: '已结束', EXPIRED: '已释放', FAILED: '任务异常' })[session.value?.status || 'STANDBY'])
const currentFeed = computed(() => videoFeeds.find(item => item.key === activeFeed.value) || videoFeeds[0])
const visibleEvents = computed(() => (mission.value.events?.length ? mission.value.events : [
  { progress: 10, type: 'TAKEOFF', label: '到达放飞点' }, { progress: 20, type: 'SCAN_START', label: '开始协同扫描' },
  { progress: 55, type: 'ROAD_OBSTACLE', label: '发现道路障碍' }, { progress: 75, type: 'RETURN', label: '无人机开始返航' },
  { progress: 100, type: 'COMPLETE', label: '协同巡检完成' }
]).slice().reverse())
const summary = computed(() => {
  const metrics = devices.value.filter(item => item.deviceType === 'smart_drone').map(item => item.sensorData || {})
  const avg = (field, fallback) => metrics.length ? metrics.reduce((sum, item) => sum + Number(item[field] ?? fallback), 0) / metrics.length : fallback
  const deviation = devices.value.reduce((max, item) => Math.max(max, Number(item.sensorData?.routeDeviationMeters || 0)), 0)
  return { coverage: avg('coveragePercent', 0), battery: avg('battery', 100), link: avg('linkQuality', 100), deviation }
})

function number(value, digits) { const n = Number(value); return Number.isFinite(n) ? n.toFixed(digits) : '—' }
function phaseLabel(value) { return ({ DEPART: '编组出发', TAKEOFF: '定点放飞', SCANNING: '协同扫描', RETURNING: '返航会合', DOCKED: '入舱待命' })[value] || '等待启动' }
function phaseState(phase, index) {
  const current = phases.findIndex(item => item.key === mission.value.missionPhase)
  return { active: index === current && session.value?.status === 'RUNNING', complete: session.value?.status === 'COMPLETED' || (current >= 0 && index < current) }
}
function pairStatus(pair) { const device = devices.value.find(item => item.deviceId === pair.droneId); return device ? phaseLabel(device.sensorData?.missionPhase) : '待命' }
function eventText(event) { return event.confidence ? `可信度 ${Math.round(event.confidence * 100)}%` : event.deviceId || (event.reached ? '事件已触发' : '等待任务进度') }
function selectPair(pair) { selectedId.value = pair.droneId; activeFeed.value = pair.droneId.endsWith('3') ? 'patrol_uav_cam_01' : 'patrol_uav_cam_02' }
function openDeviceVideo(device) { if (device?.deviceType !== 'smart_drone') return; activeFeed.value = device.deviceId.endsWith('3') ? 'patrol_uav_cam_01' : 'patrol_uav_cam_02' }

function applySnapshot(payload) {
  if (!payload) return
  if (payload.session) { session.value = payload.session; timeScale.value = Number(payload.session.timeScale || 1) }
  if (payload.mission) mission.value = payload.mission
  if (payload.devices) devices.value = payload.devices
  if (!selectedId.value && devices.value.length) selectedId.value = devices.value[0].deviceId
}
async function refreshMission() { if (!session.value?.id) return; applySnapshot(await getMission(session.value.id)) }
function connectEvents() {
  eventSource?.close()
  if (!session.value?.id || isTerminal.value) return
  eventSource = new EventSource(sessionEventsUrl(session.value.id), { withCredentials: true })
  eventSource.addEventListener('snapshot', event => applySnapshot(JSON.parse(event.data)))
  eventSource.addEventListener('session-end', event => { applySnapshot(JSON.parse(event.data)); eventSource?.close() })
  eventSource.onerror = () => { notice.value = '实时链路正在重连，已保留最近一次任务数据。' }
}
async function startMission() { busy.value = true; notice.value = ''; try { applySnapshot(await createSession()); connectEvents(); await refreshMission() } catch (error) { notice.value = error.message } finally { busy.value = false } }
async function updateSpeed() { try { applySnapshot(await changeSpeed(session.value.id, timeScale.value)) } catch (error) { notice.value = error.message } }
async function restartMission() { busy.value = true; try { applySnapshot(await restartSession(session.value.id)); connectEvents() } catch (error) { notice.value = error.message } finally { busy.value = false } }
async function endMission() { if (!session.value?.id) return; try { applySnapshot(await stopSession(session.value.id)); eventSource?.close() } catch (error) { notice.value = error.message } }

async function activateDesktop() {
  if (pollTimer) return
  try { applySnapshot(await getCurrentSession()); if (session.value?.id) { await refreshMission(); connectEvents() } } catch (error) { notice.value = '服务正在启动，请稍后刷新。' }
  pollTimer = window.setInterval(() => refreshMission().catch(() => {}), 5000)
}

onMounted(async () => {
  desktopQuery = window.matchMedia('(min-width: 1100px)')
  const updateDesktop = event => { desktopReady.value = event.matches; if (event.matches) activateDesktop() }
  desktopReady.value = desktopQuery.matches
  desktopQuery.addEventListener('change', updateDesktop)
  desktopQuery._zhixunListener = updateDesktop
  if (!desktopReady.value) return
  await activateDesktop()
})
onBeforeUnmount(() => {
  eventSource?.close()
  if (pollTimer) window.clearInterval(pollTimer)
  if (desktopQuery?._zhixunListener) desktopQuery.removeEventListener('change', desktopQuery._zhixunListener)
})
</script>
