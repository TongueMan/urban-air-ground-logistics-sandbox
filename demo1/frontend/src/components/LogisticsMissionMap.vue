<template>
  <div class="mission-map" :class="{ 'is-replay': isReplay, 'is-planning-preview': planningPreview }">
    <div ref="mapEl" class="map-canvas" data-tutorial-id="mission-map-interaction" tabindex="-1" aria-label="配送任务地图，可点击车辆或无人机进入跟随视角"></div>
    <div v-if="mapLoading" class="map-status"><strong>三维协同场景启动中</strong><small>BAIDU MAPV THREE · LOGISTICS MISSION</small></div>
    <div v-if="mapError" class="map-error">{{ mapError }}</div>

    <div class="layer-switches" aria-label="地图图层">
      <label><input v-model="layers.planned" type="checkbox">计划路线</label>
      <label><input v-model="layers.actual" type="checkbox">实际轨迹</label>
      <label><input v-model="layers.scan" type="checkbox">服务范围</label>
      <label><input v-model="layers.airspace" type="checkbox">数字空域</label>
      <label><input v-model="layers.traffic" type="checkbox">路口信号</label>
    </div>
    <div v-if="trafficStatusLabel" class="traffic-status" :class="trafficStatusClass">{{ trafficStatusLabel }}</div>
    <div v-if="planningPreview && previewAirspaceCount" class="planning-airspace-status" :class="{ 'is-hidden': !layers.airspace }" role="status">
      <i aria-hidden="true"></i>
      <span><strong>{{ layers.airspace ? '规划空域已显示' : '规划空域已隐藏' }}</strong><small>{{ previewAirspaceCount }} 处禁飞 / 受限区域</small></span>
    </div>
    <div id="mission-map-legend" class="map-legend" aria-label="地图图例">
      <span><i class="ground-plan"></i>地面计划</span><span><i class="ground-actual"></i>车辆实际</span>
      <span><i class="air-plan"></i>空中计划</span><span><i class="air-actual"></i>无人机实际</span>
      <span><i class="no-fly"></i>{{ planningPreview ? '规划禁飞 / 受限空域' : '受限空域体积' }}</span>
      <span><i class="reward-coin"></i>配送收益金币</span><span><i class="reward-diamond"></i>粉钻奖励</span>
    </div>
    <div v-if="followingId" class="follow-controls" aria-label="跟随视角" title="跟随中可使用鼠标滚轮缩放距离">
      <span>跟随 {{ followingDevice?.deviceName || followingId }}</span>
      <button type="button" :class="{ active: followMode === 'side' }" :aria-pressed="followMode === 'side'" @click="setFollowMode('side')">侧后方</button>
      <button type="button" :class="{ active: followMode === 'rear' }" :aria-pressed="followMode === 'rear'" @click="setFollowMode('rear')">正后方</button>
      <button class="overview-button" type="button" data-tutorial-id="return-mission-overview" @click="leaveFollow">返回总览 <kbd>Esc</kbd></button>
    </div>

  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js'
import { clone as cloneSkeleton } from 'three/examples/jsm/utils/SkeletonUtils.js'
import * as mapvthree from '@baidumap/mapv-three'
import { runtimeConfig, staticAssetCandidates } from '../config/runtime'
import { createBaiduCyberProvider } from '../config/baiduCyberMap'
import { ACTIVE_MODEL_ROLES, getModelAsset, mapPresentationForAsset } from '../config/modelAssets.mjs'
import { centerSceneForTransform } from '../utils/modelSceneTransforms.mjs'
import { missionOverviewCamera, missionViewportOptions, plannerAwareViewportPoints, planningPreviewViewportOptions } from '../utils/missionViewport.mjs'
import { resolveAirspaceActivation, visibleAirspaceConflicts } from '../utils/airspaceVisibility.mjs'
import {
  adjustFollowZoomScale,
  createPolylineSampler,
  executableAirRouteFraction,
  hasMissionSimulation,
  interpolateHeading,
  missionViewState,
  modelYawRadians,
  modelVisibilityScale,
  movementHeadingDegrees,
  missionFollowCamera,
  missionModelPhaseState,
  polylineGeometryKey,
  selectMissionActualPoints,
  telemetryMotionRatio,
  telemetryTransitionDuration
} from '../utils/missionMotion.mjs'

const props = defineProps({
  devices: { type: Array, default: () => [] },
  mission: { type: Object, default: () => ({}) },
  selectedId: { type: String, default: '' },
  selectedAirspaceId: { type: String, default: '' },
  timeCursor: { type: Number, default: 100 },
  timeMode: { type: String, default: 'LIVE' },
  planningPreview: { type: Boolean, default: false },
  tutorialRedConflictLocked: { type: Boolean, default: false },
  modelAssignments: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['select', 'select-airspace', 'follow-change'])
const mapEl = ref(null), mapError = ref(''), mapLoading = ref(true), replayPercent = ref(100), followingId = ref(''), followMode = ref('rear')
const layers = reactive({ planned: true, actual: true, scan: true, airspace: true, traffic: true })
let engine = null, mapView = null, resizeObserver = null, fitted = false, prepareRenderListener = null, modelTemplates = null, followZoomScale = 1, glowTexture = null, mapDragging = false
let currentMissionViewportPoints = []
let hitLayer = null, pickLayer = null, missionPointLayer = null, missionPointSignature = '', rewardPopupLayer = null, trafficLightLayer = null, trafficLightStructureSignature = ''
let airspaceLabelLayer = null, conflictLabelLayer = null, airspaceStructureSignature = '', airspaceLabelSignature = '', conflictLabelSignature = ''
let liveTrafficLights = [], trafficLightRenderedStateSignature = '', trafficLightRenderedNode = null
const routeLayers = new Map(), routeSamplers = new Map(), modelRecords = new Map(), coinRecords = new Map(), coinPopups = new Map(), airspaceVisuals = new Map(), gltfLoader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder)
const pointLayerSignatures = { hit: '', pick: '' }
const MODEL_ASSET_REVISION = 'fleet-deployment-v1'
const COIN_ASSET_ID = 'gold-coin'
const DIAMOND_ASSET_ID = 'pink-diamond'
const COIN_SPIN_RADIANS_PER_SECOND = Math.PI * 2 * .45
const DIAMOND_SPIN_RADIANS_PER_SECOND = Math.PI * 2 * .32
const DIAMOND_UPRIGHT_ROTATION_X = Math.PI / 2
const modelTemplateLoads = new Map()
let coinTaskKey = '', coinSnapshotInitialized = false, knownCollectedPointIds = new Set()
const ROLE_COLORS = {
  ground_vehicle: new THREE.Color('#39e6ff'),
  smart_drone: new THREE.Color('#d46cff'),
  selected: new THREE.Color('#ffd166')
}
const OVERVIEW = { center: [117.285, 31.842, 0], heading: 12, pitch: 70, range: 12500 }
const TRAFFIC_LIGHT_MAX_RANGE = 3000
const TRAFFIC_LIGHT_STATUSES = new Set(['RED', 'YELLOW', 'GREEN', 'FLASHING_YELLOW', 'OFF'])
const TRAFFIC_LIGHT_STATUS_CLASSES = ['is-red', 'is-yellow', 'is-green', 'is-flashing-yellow', 'is-off']
const TRAFFIC_MOVEMENT_LABELS = { STRAIGHT: '直行', LEFT: '左转', RIGHT: '右转', U_TURN: '掉头' }
const TRAFFIC_MOVEMENT_ARROWS = { STRAIGHT: '↑', LEFT: '←', RIGHT: '→', U_TURN: '↶' }
const MAP_UP = new THREE.Vector3(0, 0, 1)
const AIRSPACE_LABEL_ALTITUDE_METERS = 12
const CONFLICT_LABEL_ALTITUDE_METERS = 18

function roleForDevice(device) { return device.deviceType === 'smart_drone' ? 'smart_drone' : 'ground_vehicle' }
function modelAssignmentForDevice(device) {
  const role = roleForDevice(device)
  const assignment = props.modelAssignments?.[role]
  const assetId = assignment?.modelAssetId || ACTIVE_MODEL_ROLES[role]
  return {
    role,
    assetId,
    independentAir: role === 'smart_drone' && assignment?.independentRoute === true
  }
}
function presentationForAsset(assetId, role) {
  try { return mapPresentationForAsset(assetId) }
  catch { return mapPresentationForAsset(ACTIVE_MODEL_ROLES[role]) }
}
const hasSimulation = computed(() => hasMissionSimulation(props.mission))
const isReplay = computed(() => hasSimulation.value && (props.timeMode === 'REPLAY'
  || ['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(String(props.mission.state || '').toUpperCase())))
const followingDevice = computed(() => props.devices.find(item => item.deviceId === followingId.value))
const presentation = computed(() => missionViewState(props.mission, props.devices, replayPercent.value, {
  forceReplay: props.timeMode === 'REPLAY',
  replayTimeMs: props.timeMode === 'REPLAY' ? replaySimulationTimeMs() : null
}))
const latestMetrics = computed(() => presentation.value.metricsById)
const trafficStatus = computed(() => props.mission?.trafficLightStatus || {})
const trafficStatusLabel = computed(() => {
  const state = String(trafficStatus.value.state || '').toUpperCase()
  if (!state) return ''
  if (state === 'READY') return `路口信号仿真 · ${Number(trafficStatus.value.liveLightCount || 0)} 处`
  return String(trafficStatus.value.message || '路口信号仿真准备中')
})
const trafficStatusClass = computed(() => `is-${String(trafficStatus.value.state || 'unknown').toLowerCase()}`)
const previewAirspaceCount = computed(() => {
  const runtime = props.mission?.airspace?.runtimeVolumes
  if (Array.isArray(runtime) && runtime.length) return runtime.length
  const frozen = props.mission?.airspace?.volumes
  if (Array.isArray(frozen) && frozen.length) return frozen.length
  return Array.isArray(props.mission?.airspace?.noFlyZones) ? props.mission.airspace.noFlyZones.length : 0
})
function replaySimulationTimeMs() {
  const timeline = props.mission?.timeline || {}
  const duration = Number(props.mission?.replayDurationMs ?? timeline.liveSimulationTimeMs ?? 0)
  const head = Math.max(0.1, Number(timeline.liveProgress ?? 100))
  return duration * Math.max(0, Math.min(head, replayPercent.value)) / head
}
function visibleActualPoints(route) {
  return selectMissionActualPoints(route, props.mission, replayPercent.value, props.timeMode === 'REPLAY',
    props.timeMode === 'REPLAY' ? replaySimulationTimeMs() : null)
}
function coordinates(points, defaultHeight = 0) {
  return (points || []).filter(point => point != null).map(point => Array.isArray(point)
    ? [Number(point[0]), Number(point[1]), Number(point[2] ?? defaultHeight)]
    : [Number(point.longitude), Number(point.latitude), Number(point.altitude ?? defaultHeight)])
    .filter(point => point.every(Number.isFinite))
}
function routeCoordinates(route, points = route?.points || []) {
  const air = route?.kind === 'AIR'
  return coordinates(points, air ? 70 : .3).map(point => air
    ? [point[0], point[1], Math.max(3, point[2])]
    : [point[0], point[1], .3])
}
function featureCollection(features) { return { type: 'FeatureCollection', features } }
function lineSource(points, id) {
  return mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(points.length > 1 ? [{ type: 'Feature', id, geometry: { type: 'LineString', coordinates: points }, properties: { id } }] : []))
}
function multiLineSource(segments, id) {
  return mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection((segments || []).filter(points => points.length > 1).map((points, index) => ({
    type: 'Feature', id: `${id}-${index + 1}`, geometry: { type: 'LineString', coordinates: points }, properties: { id, index }
  }))))
}
function noFlyZoneSource(zones = []) {
  const features = zones.map((zone, index) => ({
    type: 'Feature',
    id: `no-fly-zone-${index + 1}`,
    geometry: { type: 'Polygon', coordinates: [coordinates(zone, 1).map(point => [point[0], point[1], 1])] },
    properties: { id: `no-fly-zone-${index + 1}` }
  })).filter(feature => feature.geometry.coordinates[0].length >= 4)
  return mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(features))
}
function airspaceVolumes() {
  const runtime = props.mission?.airspace?.runtimeVolumes
  if (Array.isArray(runtime) && runtime.length) return runtime
  const frozen = props.mission?.airspace?.volumes
  if (Array.isArray(frozen) && frozen.length) {
    if (props.planningPreview) {
      return frozen.map(volume => ({ ...volume, state: 'PLANNED', activationRatio: 1, threatLevel: 'NORMAL', planningPreview: true }))
    }
    const replayTime = isReplay.value && props.timeMode === 'REPLAY' ? replaySimulationTimeMs() : 0
    return frozen.map(volume => {
      const from = Number(volume.activeFromSimulationMs || 0), until = volume.activeUntilSimulationMs == null ? Infinity : Number(volume.activeUntilSimulationMs)
      const lead = Number(volume.activationLeadMs || 0), expansion = Math.max(1, Number(volume.expansionDurationMs || 1))
      if (volume.dynamic === true && volume.ruleType === 'TEMPORARY_NO_FLY' && Number(volume.cyclePeriodMs) > 0) {
        const anchor = Number(volume.cycleAnchorSimulationMs ?? from)
        const period = Math.max(1, Number(volume.cyclePeriodMs))
        const activeDuration = Math.min(period, Math.max(1, Number(volume.activeDurationMs ?? (until - from))))
        const contraction = Math.min(activeDuration, Math.max(1, Number(volume.contractionDurationMs || 1)))
        if (replayTime < anchor) return { ...volume, state: 'SCHEDULED', activationRatio: 0, startsInMs: anchor - replayTime, remainingMs: activeDuration, threatLevel: 'NORMAL' }
        const cursor = ((replayTime - anchor) % period + period) % period
        const cycleStart = replayTime - cursor
        const state = cursor < expansion ? 'ACTIVATING' : cursor < activeDuration - contraction ? 'ACTIVE' : cursor < activeDuration ? 'CLEARING' : 'SCHEDULED'
        const activationRatio = state === 'ACTIVATING' ? Math.max(.05, Math.min(.95, cursor / expansion))
          : state === 'ACTIVE' ? 1
            : state === 'CLEARING' ? Math.max(.05, Math.min(.95, (activeDuration - cursor) / contraction)) : 0
        const nextStart = state === 'SCHEDULED' ? cycleStart + period : cycleStart
        return { ...volume, state, activationRatio, activeFromSimulationMs: nextStart, activeUntilSimulationMs: nextStart + activeDuration,
          startsInMs: state === 'SCHEDULED' ? nextStart - replayTime : 0, remainingMs: state === 'SCHEDULED' ? activeDuration : Math.max(0, cycleStart + activeDuration - replayTime), threatLevel: 'NORMAL' }
      }
      const state = replayTime >= until ? 'EXPIRED' : replayTime < from - lead ? 'SCHEDULED' : replayTime < from ? 'ACTIVATING' : 'ACTIVE'
      const activationRatio = state === 'ACTIVE' ? Math.min(1, (replayTime - from) / expansion) : state === 'ACTIVATING' ? Math.max(.05, 1 - (from - replayTime) / Math.max(lead, expansion)) : 0
      return { ...volume, state, activationRatio, remainingMs: Number.isFinite(until) ? Math.max(0, until - replayTime) : null, threatLevel: 'NORMAL' }
    })
  }
  return (props.mission?.airspace?.noFlyZones || []).map((footprint, index) => ({ id: `BASE-NFZ-${index + 1}`, label: '基础禁飞区', ruleType: 'ABSOLUTE_NO_FLY', footprint, floorMeters: 0, ceilingMeters: 80, state: 'ACTIVE', activationRatio: 1, threatLevel: 'NORMAL', blocking: true }))
}
function volumeCenter(volume) {
  const points = coordinates(volume?.footprint, 0)
  if (!points.length) return [0, 0, 0]
  const count = points.length > 1 && points[0][0] === points.at(-1)[0] && points[0][1] === points.at(-1)[1] ? points.length - 1 : points.length
  return [points.slice(0, count).reduce((sum, point) => sum + point[0], 0) / count, points.slice(0, count).reduce((sum, point) => sum + point[1], 0) / count, Number(volume.floorMeters || 0)]
}
function airspaceLabelSource(volumes = []) {
  const data = mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(volumes.map(volume => ({
    type: 'Feature', id: volume.id,
    geometry: { type: 'Point', coordinates: [volumeCenter(volume)[0], volumeCenter(volume)[1], AIRSPACE_LABEL_ALTITUDE_METERS] },
    properties: { volumeId: volume.id, volumeLabel: volume.label, ruleType: volume.ruleType, state: volume.state, ceiling: volume.ceilingMeters, threatLevel: volume.threatLevel, planningPreview: volume.planningPreview === true,
      corridorFloor: volume.corridorFloorMeters, corridorCeiling: volume.corridorCeilingMeters,
      targetAltitude: volume.targetAltitudeMeters, currentAltitude: volume.currentAltitudeMeters }
  }))))
  ;['volumeId', 'volumeLabel', 'ruleType', 'state', 'ceiling', 'threatLevel', 'planningPreview', 'corridorFloor', 'corridorCeiling', 'targetAltitude', 'currentAltitude'].forEach(attribute => data.defineAttribute(attribute, attribute))
  return data
}
function renderAirspaceLabel(item) {
  const attributes = item?.attributes || item
  const node = document.createElement('button')
  const planning = attributes.planningPreview === true || attributes.planningPreview === 'true'
  node.type = 'button'; node.className = `airspace-marker is-${String(attributes.ruleType || '').toLowerCase()} threat-${String(attributes.threatLevel || 'normal').toLowerCase()}${planning ? ' is-planning' : ''}`
  node.dataset.volumeId = String(attributes.volumeId || '')
  const corridor = String(attributes.ruleType || '') === 'ALTITUDE_CORRIDOR'
  const current = Number(attributes.currentAltitude)
  const detail = planning && corridor
    ? `规划走廊 · 合法 ${Math.round(Number(attributes.corridorFloor || 0))}–${Math.round(Number(attributes.corridorCeiling || 0))}m`
    : planning
      ? `规划空域 · 0–${Math.round(Number(attributes.ceiling || 0))}m`
      : corridor
        ? `LEGAL ${Math.round(Number(attributes.corridorFloor || 0))}–${Math.round(Number(attributes.corridorCeiling || 0))}m · 当前 ${Number.isFinite(current) ? `${Math.round(current)}m` : '—'} · 目标 ${Math.round(Number(attributes.targetAltitude || 0))}m`
        : `${String(attributes.state || 'ACTIVE')} · ${Math.round(Number(attributes.ceiling || 0))}m`
  node.innerHTML = `<b>${String(attributes.volumeId || 'AIRSPACE')}</b><span>${detail}</span>`
  node.setAttribute('aria-label', corridor
    ? `${attributes.volumeLabel || attributes.volumeId}，合法高度${attributes.corridorFloor}到${attributes.corridorCeiling}米，目标${attributes.targetAltitude}米`
    : `${attributes.volumeLabel || attributes.volumeId}，${attributes.state}，上限${attributes.ceiling}米`)
  node.addEventListener('click', event => { event.stopPropagation(); emit('select-airspace', String(attributes.volumeId || '')) })
  return node
}
function conflictMarkerCoordinate(conflict) {
  const point = coordinates([conflict?.entryPoint], CONFLICT_LABEL_ALTITUDE_METERS)[0]
  return point ? [point[0], point[1], CONFLICT_LABEL_ALTITUDE_METERS] : null
}
function conflictLabelSource(conflicts = []) {
  const data = mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(conflicts.map(conflict => ({
    type: 'Feature', id: conflict.id, geometry: { type: 'Point', coordinates: conflictMarkerCoordinate(conflict) },
    properties: { volumeId: conflict.volumeId, distance: Math.round(Number(conflict.distanceMeters || 0)), eta: Math.round(Number(conflict.estimatedEntrySeconds || 0)), threatLevel: conflict.threatLevel, ruleType: conflict.ruleType,
      predictive: conflict.predictive === true, currentlyActive: conflict.currentlyActive === true }
  })).filter(feature => feature.geometry.coordinates)))
  ;['volumeId', 'distance', 'eta', 'threatLevel', 'ruleType', 'predictive', 'currentlyActive'].forEach(attribute => data.defineAttribute(attribute, attribute))
  return data
}
function renderConflictLabel(item) {
  const attributes = item?.attributes || item
  const node = document.createElement('button'); node.type = 'button'; node.className = `airspace-conflict-marker threat-${String(attributes.threatLevel || 'conflict').toLowerCase()}`
  if (String(attributes.ruleType || '') === 'ABSOLUTE_NO_FLY') node.dataset.tutorialId = 'red-airspace-conflict'
  const predictive = attributes.predictive === true || attributes.predictive === 'true'
  const active = attributes.currentlyActive === true || attributes.currentlyActive === 'true'
  const title = document.createElement('b'); title.textContent = predictive ? '◇  预测冲突' : '×  空域冲突'
  const detail = document.createElement('span'); detail.textContent = `${attributes.distance} 米 · 预计 ${attributes.eta} 秒`
  node.setAttribute('aria-label', `${predictive ? '预测空域冲突' : '空域冲突'}，${active ? '空域当前生效' : '空域当前未生效'}，预计${attributes.eta}秒后到达`)
  node.append(title, detail); node.addEventListener('click', event => { event.stopPropagation(); emit('select-airspace', String(attributes.volumeId || '')) })
  return node
}
function pointSource(points) {
  return mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(points.map(item => ({ type: 'Feature', id: item.deviceId, geometry: { type: 'Point', coordinates: item.coordinate }, properties: { deviceId: item.deviceId } }))))
}
function missionPointSource(points = []) {
  const data = mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(points.map(item => ({
    type: 'Feature',
    id: item.id,
    geometry: { type: 'Point', coordinates: item.coordinate },
    properties: { markerId: item.id, markerKind: item.kind, markerLabel: item.label }
  }))))
  ;['markerId', 'markerKind', 'markerLabel'].forEach(attribute => data.defineAttribute(attribute, attribute))
  return data
}
function rewardPopupSource(popups = []) {
  const data = mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(popups.map(popup => ({
    type: 'Feature', id: popup.id,
    geometry: { type: 'Point', coordinates: popup.coordinate },
    properties: { popupId: popup.id, amountMinor: popup.amountMinor, rewardKind: popup.rewardKind }
  }))))
  ;['popupId', 'amountMinor', 'rewardKind'].forEach(attribute => data.defineAttribute(attribute, attribute))
  return data
}
function renderRewardPopup(item) {
  const attributes = item?.attributes || item
  const node = document.createElement('div')
  node.className = `coin-reward-popup${String(attributes.rewardKind || '').toUpperCase() === 'DIAMOND' ? ' is-diamond' : ''}`
  node.textContent = `+${new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(attributes.amountMinor || 0) / 100)}`
  node.setAttribute('role', 'status')
  return node
}
function trafficLightSource(lights = []) {
  const data = mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(lights.map(light => {
    const status = String(light.lampStatus || 'OFF').toUpperCase()
    const countdown = Number(light.countdown)
    return {
      type: 'Feature',
      id: light.id,
      geometry: { type: 'Point', coordinates: [Number(light.longitude), Number(light.latitude), 9] },
      properties: {
        signalId: String(light.id || ''),
        status,
        countdown: Number.isFinite(countdown) && countdown >= 0 ? Math.floor(countdown) : null,
        movement: String(light.movement || 'STRAIGHT').toUpperCase(),
        signalType: String(light.signalType || 'CIRCULAR').toUpperCase(),
        canTurnOnRed: light.canTurnOnRed === true,
        linkId: String(light.linkId || '')
      }
    }
  })))
  ;['signalId', 'status', 'countdown', 'movement', 'signalType', 'canTurnOnRed', 'linkId']
    .forEach(attribute => data.defineAttribute(attribute, attribute))
  return data
}
function trafficLightView(light = {}) {
  const attributes = light?.attributes || light
  const rawStatus = String(attributes.status || attributes.lampStatus || 'OFF').toUpperCase()
  const status = TRAFFIC_LIGHT_STATUSES.has(rawStatus) ? rawStatus : 'OFF'
  const countdown = Number(attributes.countdown)
  return {
    signalId: String(attributes.signalId || attributes.id || ''),
    status,
    countdown: Number.isFinite(countdown) && countdown >= 0 ? Math.floor(countdown) : null,
    movement: String(attributes.movement || 'STRAIGHT').toUpperCase(),
    signalType: String(attributes.signalType || 'CIRCULAR').toUpperCase(),
    canTurnOnRed: attributes.canTurnOnRed === true
  }
}
function applyTrafficLightState(node, light) {
  const attributes = trafficLightView(light)
  const { status, movement, signalType, countdown } = attributes
  node.dataset.signalId = attributes.signalId
  node.classList.remove(...TRAFFIC_LIGHT_STATUS_CLASSES)
  node.classList.add(`is-${status.toLowerCase().replace('_', '-')}`)
  const readableStatus = status === 'FLASHING_YELLOW' ? '黄闪' : { RED: '红灯', YELLOW: '黄灯', GREEN: '绿灯', OFF: '信号灯关闭' }[status]
  node.setAttribute('aria-label', `${TRAFFIC_MOVEMENT_LABELS[movement] || '前行'}${readableStatus}${countdown !== null ? `，剩余${countdown}秒` : ''}`)
  node.title = attributes.canTurnOnRed && status === 'RED'
    ? '圆形红灯右转：确认不妨碍放行车辆和行人后通行'
    : `${TRAFFIC_MOVEMENT_LABELS[movement] || '前行'} · ${readableStatus}`

  const lamp = node.querySelector('.traffic-signal-lamp')
  let arrow = lamp?.querySelector('.traffic-signal-arrow')
  if (signalType === 'DIRECTIONAL') {
    if (!arrow) { arrow = document.createElement('span'); arrow.className = 'traffic-signal-arrow'; lamp?.appendChild(arrow) }
    arrow.textContent = TRAFFIC_MOVEMENT_ARROWS[movement] || '↑'
  } else arrow?.remove()
  const digits = node.querySelector('.traffic-signal-countdown')
  if (digits) digits.textContent = countdown !== null ? String(Math.max(0, Math.min(99, countdown))).padStart(2, '0') : '--'
}
function renderTrafficLight(light) {
  const node = document.createElement('div')
  node.className = 'traffic-signal-marker'
  node.setAttribute('role', 'img')
  node.setAttribute('aria-hidden', 'true')
  const lamp = document.createElement('span'); lamp.className = 'traffic-signal-lamp'
  const digits = document.createElement('span')
  digits.className = 'traffic-signal-countdown'
  node.append(lamp, digits)
  applyTrafficLightState(node, light)
  return node
}
function renderMissionPoint(item) {
  const attributes = item?.attributes || item
  const kind = String(attributes.markerKind || 'TARGET').toLowerCase()
  const node = document.createElement('div')
  node.className = `mission-point-marker is-${kind}`
  node.setAttribute('role', 'img')
  node.setAttribute('aria-label', String(attributes.markerLabel || '任务点'))
  const icon = document.createElement('span'); icon.className = 'mission-point-icon'
  icon.textContent = kind === 'launch' ? '↑' : kind === 'recovery' ? '↓' : '◆'
  const label = document.createElement('span'); label.className = 'mission-point-label'
  label.textContent = String(attributes.markerLabel || '任务点')
  if (kind !== 'target') node.append(icon)
  node.append(label)
  return node
}
function syncTrafficLightNodes() {
  if (!trafficLightLayer) return
  const nodes = trafficLightLayer.nodes || []
  if (nodes.length !== liveTrafficLights.length) return
  const stateSignature = liveTrafficLights.map(light => `${light.id}:${light.lampStatus}:${light.countdown ?? ''}:${light.canTurnOnRed === true}`).join('|')
  if (stateSignature === trafficLightRenderedStateSignature && nodes[0] === trafficLightRenderedNode) return
  const lightsById = new Map(liveTrafficLights.map(light => [String(light.id || ''), light]))
  nodes.forEach((node, index) => applyTrafficLightState(node, lightsById.get(node.dataset.signalId) || liveTrafficLights[index]))
  trafficLightRenderedStateSignature = stateSignature
  trafficLightRenderedNode = nodes[0] || null
}
function syncTrafficLightVisibility() {
  if (!trafficLightLayer || !engine) return
  const range = Number(engine.map.getRange?.())
  const shouldShow = layers.traffic && Number.isFinite(range) && range <= TRAFFIC_LIGHT_MAX_RANGE
  ;(trafficLightLayer.nodes || []).forEach(node => {
    node.classList.toggle('is-zoom-visible', shouldShow)
    node.setAttribute('aria-hidden', shouldShow ? 'false' : 'true')
  })
}
function pairDeviceIds() {
  const focusId = props.selectedId || followingId.value
  if (!focusId) return new Set()
  const pair = (props.mission.pairs || []).find(item => [item.vehicleId, item.droneId].includes(focusId))
  return new Set(pair ? [pair.vehicleId, pair.droneId] : [focusId])
}
function eventDevice(event) {
  const id = event?.entity?.value?.attributes?.deviceId || event?.entity?.pairs?.deviceId || event?.entity?.value?.id
  return props.devices.find(item => String(item.deviceId) === String(id))
}

function deviceAtMapPixel(pixel, radius = 42) {
  if (!engine || !Array.isArray(pixel)) return null
  const containerSize = engine.map.getContainerSize?.()
  const width = Number(Array.isArray(containerSize) ? containerSize[0] : containerSize?.width ?? containerSize?.x ?? mapEl.value?.clientWidth)
  const height = Number(Array.isArray(containerSize) ? containerSize[1] : containerSize?.height ?? containerSize?.y ?? mapEl.value?.clientHeight)
  const targetX = Number(pixel[0]), targetY = Number(pixel[1])
  if (![width, height, targetX, targetY].every(Number.isFinite)) return null
  let nearest = null, nearestDistance = radius
  modelRecords.forEach((record, deviceId) => {
    const ndc = record.group.position.clone().project(engine.camera)
    if (ndc.z < -1 || ndc.z > 1) return
    const screenX = (ndc.x + 1) * width / 2
    const screenY = (1 - ndc.y) * height / 2
    const distance = Math.hypot(screenX - targetX, screenY - targetY)
    if (distance <= nearestDistance) { nearest = deviceId; nearestDistance = distance }
  })
  return nearest
}

function selectDeviceForFollow(deviceId, event) {
  const device = props.devices.find(item => String(item.deviceId) === String(deviceId))
  if (!device) return
  event?.stopPropagation?.()
  if (followingId.value !== device.deviceId) { followMode.value = 'rear'; followZoomScale = 1 }
  followingId.value = device.deviceId
  emit('select', device.deviceId)
}

function tuneModelMaterial(role, assetId, node, source) {
  const material = source.clone()
  const key = `${node.name} ${material.name}`.toLowerCase()
  material.roughness = Math.min(Number(material.roughness ?? .65), .58)
  material.metalness = Math.max(Number(material.metalness ?? .15), .22)
  if (assetId === 'smart-city-drone') {
    material.color?.set('#eef3ff')
    if (material.emissive) material.emissive.set('#8e3bdb')
    material.emissiveIntensity = 1.2
    material.roughness = .34
    material.metalness = .38
  } else if (!['tricycle', 'ford-f350-utility'].includes(assetId)) {
    // Fleet Hub models keep their authored paint and texture colors on the map.
    // Only a very small emissive lift is added so the vehicle remains readable
    // against the dark cyber-map without flattening the original materials.
    material.emissiveIntensity = Math.max(Number(material.emissiveIntensity || 0), material.map ? .05 : .1)
  } else if (key.includes('glass') || key.includes('cabin')) {
    material.color?.set('#174c68')
    if (material.emissive) material.emissive.set('#0a617d')
    material.emissiveIntensity = .34
    material.transparent = true
    material.opacity = .82
    material.roughness = .18
  } else if (key.includes('headlight')) {
    material.color?.set('#e8fdff')
    if (material.emissive) material.emissive.set('#b9f8ff')
    material.emissiveIntensity = 2.15
    material.roughness = .18
  } else if (key.includes('taillight') || key.includes('tailight')) {
    material.color?.set('#ff5871')
    if (material.emissive) material.emissive.set('#ff244c')
    material.emissiveIntensity = 1.55
    material.roughness = .24
  } else if (key.includes('wheel') || key.includes('trim')) {
    material.color?.set('#17242d')
    if (material.emissive) material.emissive.set('#08728b')
    material.emissiveIntensity = .42
    material.roughness = .72
    material.metalness = .24
  } else {
    material.color?.set('#9beeff')
    if (material.emissive) material.emissive.set('#087f9e')
    material.emissiveIntensity = .78
    material.roughness = .34
    material.metalness = .42
  }
  material.userData.baseOpacity = Number(material.opacity ?? 1)
  material.userData.baseTransparent = Boolean(material.transparent)
  material.userData.baseEmissiveIntensity = Number(material.emissiveIntensity ?? 0)
  material.needsUpdate = true
  return material
}

async function loadTemplate(assetId, role) {
  const asset = getModelAsset(assetId)
  const gltf = await loadStaticGltf(asset.path)
  const root = gltf.scene
  root.traverse(node => {
    if (!node.isMesh || !node.material) return
    node.castShadow = false
    node.receiveShadow = false
    node.material = Array.isArray(node.material)
      ? node.material.map(material => tuneModelMaterial(role, assetId, node, material))
      : tuneModelMaterial(role, assetId, node, node.material)
  })
  const box = new THREE.Box3().setFromObject(root)
  const size = box.getSize(new THREE.Vector3())
  const center = box.getCenter(new THREE.Vector3())
  // Route coordinates anchor the model group's origin. Imported GLBs do not
  // consistently place that origin at the visual centre, which leaves the
  // aircraft beside its route marker after the visibility scale is applied.
  // Centre the template before cloning; the later grounding pass restores the
  // correct vertical placement without reintroducing a horizontal offset.
  const transformRoot = centerSceneForTransform(root, center)
  return {
    scene: transformRoot,
    animations: gltf.animations || [],
    skinned: Number(asset.scene?.skins || 0) > 0,
    normalizedScale: presentationForAsset(assetId, role).size / Math.max(size.x, size.y, size.z, .001)
  }
}
async function loadStaticGltf(path) {
  let lastError
  for (const candidate of staticAssetCandidates(path)) {
    try {
      return await gltfLoader.loadAsync(`${candidate}?v=${MODEL_ASSET_REVISION}`)
    } catch (error) {
      lastError = error
    }
  }
  throw lastError || new Error(`Unable to load ${path}`)
}
async function ensureModelTemplate(assetId, role) {
  if (!modelTemplates || modelTemplates.has(assetId)) return modelTemplates?.get(assetId)
  if (!modelTemplateLoads.has(assetId)) {
    modelTemplateLoads.set(assetId, loadTemplate(assetId, role).catch(error => {
      console.warn(`[LogisticsMissionMap] ${assetId} 模型加载失败，已降级为几何标记`, error)
      return null
    }).then(template => {
      modelTemplates?.set(assetId, template)
      modelTemplateLoads.delete(assetId)
      return template
    }))
  }
  return modelTemplateLoads.get(assetId)
}
async function ensureRewardTemplate(assetId) {
  if (!modelTemplates || modelTemplates.has(assetId)) return modelTemplates?.get(assetId)
  if (!modelTemplateLoads.has(assetId)) {
    const asset = getModelAsset(assetId)
    modelTemplateLoads.set(assetId, loadStaticGltf(asset.path)
      .then(gltf => {
        const scene = gltf.scene
        scene.traverse(node => {
          if (!node.isMesh) return
          node.castShadow = false
          node.receiveShadow = false
          node.renderOrder = 9
        })
        const box = new THREE.Box3().setFromObject(scene)
        const size = box.getSize(new THREE.Vector3())
        const center = box.getCenter(new THREE.Vector3())
        const transformRoot = centerSceneForTransform(scene, center)
        return { scene: transformRoot, normalizedScale: 1 / Math.max(size.x, size.y, size.z, .001) }
      })
      .catch(error => {
        console.warn(`[LogisticsMissionMap] ${asset.displayName} 模型加载失败，已隐藏奖励视觉`, error)
        return null
      })
      .then(template => {
        modelTemplates?.set(assetId, template)
        modelTemplateLoads.delete(assetId)
        return template
      }))
  }
  return modelTemplateLoads.get(assetId)
}
async function loadAssignedModelTemplates() {
  const assignments = props.devices.map(device => modelAssignmentForDevice(device))
  await Promise.all(assignments.map(item => ensureModelTemplate(item.assetId, item.role)))
}
async function refreshAssignedModels() {
  if (!engine || !modelTemplates) return
  await loadAssignedModelTemplates()
  props.devices.forEach(device => {
    const record = modelRecords.get(device.deviceId)
    if (record && record.assetId !== modelAssignmentForDevice(device).assetId) removeModelRecord(device.deviceId)
  })
  updateRoutes()
}
function fallbackModel(type) {
  if (type === 'smart_drone') {
    const drone = new THREE.Group()
    const bodyMaterial = new THREE.MeshStandardMaterial({ color: '#e8efff', emissive: '#7038bd', emissiveIntensity: 1.05, roughness: .34, metalness: .4 })
    const frameMaterial = new THREE.MeshStandardMaterial({ color: '#34245f', emissive: '#9b57ee', emissiveIntensity: .75, roughness: .42, metalness: .5 })
    const rotorMaterial = new THREE.MeshBasicMaterial({ color: '#d8b8ff', transparent: true, opacity: .48, side: THREE.DoubleSide, depthWrite: false })
    const lightMaterial = new THREE.MeshBasicMaterial({ color: '#54f3ff' })
    ;[bodyMaterial, frameMaterial, rotorMaterial, lightMaterial].forEach(material => {
      material.userData.baseOpacity = Number(material.opacity ?? 1)
      material.userData.baseTransparent = Boolean(material.transparent)
      material.userData.baseEmissiveIntensity = Number(material.emissiveIntensity ?? 0)
    })
    const addMesh = (geometry, material, position = [0, 0, 0]) => {
      geometry.userData.instanceOwnedGeometry = true
      const mesh = new THREE.Mesh(geometry, material)
      mesh.position.set(...position)
      mesh.userData.instanceOwnedGeometry = true
      drone.add(mesh)
      return mesh
    }
    addMesh(new THREE.BoxGeometry(1.35, .42, 1.6), bodyMaterial)
    const armA = addMesh(new THREE.BoxGeometry(3.35, .14, .18), frameMaterial)
    const armB = addMesh(new THREE.BoxGeometry(3.35, .14, .18), frameMaterial)
    armA.rotation.y = Math.PI / 4
    armB.rotation.y = -Math.PI / 4
    ;[[-1.18, .03, -1.18], [1.18, .03, -1.18], [-1.18, .03, 1.18], [1.18, .03, 1.18]].forEach((position, index) => {
      addMesh(new THREE.CylinderGeometry(.2, .24, .28, 16), frameMaterial, position)
      const rotor = addMesh(new THREE.CylinderGeometry(.62, .62, .035, 28), rotorMaterial, [position[0], .2, position[2]])
      rotor.rotation.y = index % 2 ? Math.PI / 8 : 0
    })
    addMesh(new THREE.SphereGeometry(.24, 16, 10), frameMaterial, [0, -.34, .48])
    addMesh(new THREE.SphereGeometry(.095, 12, 8), lightMaterial, [0, .02, .86])
    return drone
  }
  const material = new THREE.MeshStandardMaterial({ color: '#00c8ff', emissive: '#00a8d4', emissiveIntensity: .6 })
  material.userData.baseOpacity = 1
  material.userData.baseTransparent = false
  material.userData.baseEmissiveIntensity = .6
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(6.7, 2.55, 2.65), material)
  mesh.userData.instanceOwnedGeometry = true
  return mesh
}

function makeGlowTexture() {
  if (glowTexture) return glowTexture
  const canvas = document.createElement('canvas')
  canvas.width = 128; canvas.height = 128
  const context = canvas.getContext('2d')
  const gradient = context.createRadialGradient(64, 64, 2, 64, 64, 62)
  gradient.addColorStop(0, 'rgba(255,255,255,.96)')
  gradient.addColorStop(.2, 'rgba(255,255,255,.7)')
  gradient.addColorStop(.58, 'rgba(255,255,255,.2)')
  gradient.addColorStop(1, 'rgba(255,255,255,0)')
  context.fillStyle = gradient; context.fillRect(0, 0, 128, 128)
  glowTexture = new THREE.CanvasTexture(canvas)
  glowTexture.colorSpace = THREE.SRGBColorSpace
  return glowTexture
}

function createModelEffects(type, deviceId, coordinate) {
  const color = ROLE_COLORS[type]
  const halo = new THREE.Sprite(new THREE.SpriteMaterial({
    map: makeGlowTexture(), color, transparent: true, opacity: .4,
    depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending
  }))
  halo.renderOrder = 9; halo.frustumCulled = false
  const groundRing = new THREE.Mesh(
    new THREE.RingGeometry(.76, 1, 48),
    new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .42, side: THREE.DoubleSide, depthWrite: false })
  )
  groundRing.renderOrder = 8; groundRing.frustumCulled = false
  const altitudeLine = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]),
    new THREE.LineDashedMaterial({ color, transparent: true, opacity: .48, dashSize: 6, gapSize: 4, depthWrite: false })
  )
  altitudeLine.renderOrder = 7; altitudeLine.frustumCulled = false; altitudeLine.computeLineDistances()
  engine.add(halo); engine.add(groundRing); engine.add(altitudeLine)
  let scanRange = null
  if (type === 'smart_drone') {
    scanRange = engine.add(new mapvthree.EffectPoint({
      type: 'RadarLayered', color: '#ae69ff', sideColor: '#d67cff',
      size: Number(props.mission.serviceRadiusMeters || 250) / 3,
      height: 0, duration: 2200, keepSize: false, opacity: .14
    }))
    scanRange.dataSource = pointSource([{ deviceId, coordinate }])
    scanRange.visible = false
  }
  return { halo, groundRing, altitudeLine, scanRange }
}

function addVehicleBeacon(group, roofHeight) {
  const beacon = new THREE.Group()
  const cyan = new THREE.MeshBasicMaterial({ color: '#54f3ff', transparent: true, opacity: .95 })
  const amber = new THREE.MeshBasicMaterial({ color: '#ffbd4a', transparent: true, opacity: .48 })
  // The F-350 faces local +Y, so its warning bar must span local X across
  // the cab instead of placing the two lamps longitudinally along the roof.
  const geometry = new THREE.BoxGeometry(.62, .34, .17)
  const left = new THREE.Mesh(geometry, cyan), right = new THREE.Mesh(geometry, amber)
  left.position.set(-.33, 0, roofHeight + .13); right.position.set(.33, 0, roofHeight + .13)
  left.userData.instanceOwnedGeometry = true
  beacon.add(left, right); group.add(beacon)
  return { beacon, cyan, amber }
}

function addVehicleDroneDock(group, dockConfig) {
  const dock = new THREE.Group()
  dock.position.set(dockConfig.x, dockConfig.y, dockConfig.z + .018)
  const deckMaterial = new THREE.MeshStandardMaterial({
    color: '#143747', emissive: '#0b718b', emissiveIntensity: .42,
    metalness: .58, roughness: .46, transparent: true, opacity: .92
  })
  const ringMaterial = new THREE.MeshBasicMaterial({
    color: '#57f0ff', transparent: true, opacity: .78, side: THREE.DoubleSide, depthWrite: false
  })
  const deck = new THREE.Mesh(new THREE.CircleGeometry(dockConfig.radius, 48), deckMaterial)
  const ring = new THREE.Mesh(new THREE.RingGeometry(dockConfig.radius * .76, dockConfig.radius, 48), ringMaterial)
  deck.userData.instanceOwnedGeometry = true; ring.userData.instanceOwnedGeometry = true
  ring.position.z = .012
  dock.add(deck, ring); group.add(dock)
  return { dock, deck, ring, deckMaterial, ringMaterial }
}

function primaryMeshBottom(object) {
  let primary = null, vertexCount = -1
  object.traverse(node => {
    const count = Number(node.geometry?.attributes?.position?.count || 0)
    if (node.isMesh && count > vertexCount) { primary = node; vertexCount = count }
  })
  if (!primary) return 0
  object.updateMatrixWorld(true)
  return new THREE.Box3().setFromObject(primary).min.z
}

function cloneInstanceMaterials(object) {
  object.traverse(node => {
    if (!node.isMesh || !node.material) return
    node.material = Array.isArray(node.material) ? node.material.map(material => material.clone()) : node.material.clone()
  })
}

function createCoinRecord(point, key) {
  const isDiamond = String(point.rewardType || '').toUpperCase() === 'DIAMOND'
  const assetId = isDiamond ? DIAMOND_ASSET_ID : COIN_ASSET_ID
  const template = modelTemplates?.get(assetId)
  const coordinate = coordinates([point.position || point.coordinate], point.kind === 'AIR' ? 70 : .35)[0]
  if (!template || !coordinate) return null
  const object = template.scene.clone(true)
  cloneInstanceMaterials(object)
  object.scale.setScalar(template.normalizedScale)
  // The diamond's authored symmetry axis is Y, while the map uses Z as up.
  // Rotate only the diamond into an upright pose; the coin is already authored
  // edge-on for the map and must keep its existing orientation.
  if (isDiamond) object.rotation.x = DIAMOND_UPRIGHT_ROTATION_X
  // The imported scene is centred inside this dedicated transform root, so
  // rotating it produces true self-spin instead of orbiting around a GLB
  // authoring origin. Pink diamonds and coins deliberately share map Z as
  // their vertical spin axis.
  const spinRoot = new THREE.Group()
  spinRoot.add(object)
  const group = new THREE.Group()
  group.add(spinRoot)
  const halo = new THREE.Sprite(new THREE.SpriteMaterial({
    map: makeGlowTexture(), color: isDiamond ? '#ff43bd' : '#ffd45a', transparent: true,
    opacity: isDiamond ? .58 : point.kind === 'AIR' ? .19 : .3,
    depthWrite: false, depthTest: true, blending: THREE.AdditiveBlending
  }))
  halo.renderOrder = 8; halo.frustumCulled = false
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(.72, 1, 48),
    new THREE.MeshBasicMaterial({ color: isDiamond ? '#ff55c8' : '#ffcf4a', transparent: true,
      opacity: isDiamond ? .24 : point.kind === 'AIR' ? .13 : .27, side: THREE.DoubleSide, depthWrite: false })
  )
  ring.renderOrder = 7; ring.frustumCulled = false
  engine.add(group); engine.add(halo); engine.add(ring)
  const materials = []
  object.traverse(node => {
    if (!node.isMesh || !node.material) return
    const owned = Array.isArray(node.material) ? node.material : [node.material]
    owned.forEach(material => {
      material.userData.rewardBaseOpacity = Number(material.opacity ?? 1)
      material.transparent = true
      if (material.emissive?.set) {
        material.emissive.set(isDiamond ? '#ff168f' : '#b86a08')
        material.emissiveIntensity = Math.max(isDiamond ? 1.28 : .72, Number(material.emissiveIntensity || 0))
      }
      material.needsUpdate = true
      materials.push(material)
    })
  })
  const tier = String(point.visualTier || 'MEDIUM').toUpperCase()
  const record = {
    key, point, coordinate, group, spinRoot, object, halo, ring, materials, isDiamond,
    tier, targetPixels: isDiamond ? 50 : tier === 'SMALL' ? 28 : tier === 'LARGE' ? 44 : 36,
    worldCap: isDiamond ? 6.4 : tier === 'SMALL' ? 3.2 : tier === 'LARGE' ? 5.4 : 4.2,
    createdAt: performance.now(), lastFrameAt: performance.now(), pickupAt: 0, pickupDuration: 600
  }
  coinRecords.set(key, record)
  return record
}

function removeCoinRecord(key) {
  const record = coinRecords.get(key)
  if (!record) return
  engine?.remove(record.group); engine?.remove(record.halo); engine?.remove(record.ring)
  record.materials.forEach(material => material.dispose())
  record.halo.material.dispose(); record.ring.geometry.dispose(); record.ring.material.dispose()
  coinRecords.delete(key)
}

function updateRewardPopupLayer() {
  if (!rewardPopupLayer) return
  rewardPopupLayer.dataSource = rewardPopupSource(Array.from(coinPopups.values()))
  rewardPopupLayer.visible = coinPopups.size > 0
}

function clearCoinPopups() {
  coinPopups.forEach(popup => window.clearTimeout(popup.timer))
  coinPopups.clear()
  updateRewardPopupLayer()
}

function triggerCoinPickup(record, amountMinor) {
  if (!record || record.pickupAt) return
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  record.pickupAt = performance.now()
  record.pickupDuration = reduced ? 180 : 600
  const popup = {
    id: `reward-${record.key}`,
    coordinate: [record.coordinate[0], record.coordinate[1], record.coordinate[2] + (record.point.kind === 'AIR' ? 2.2 : 2.8)],
    amountMinor: Number(amountMinor || record.point.rewardMinor || 0),
    rewardKind: record.isDiamond ? 'DIAMOND' : 'COIN',
    timer: 0
  }
  popup.timer = window.setTimeout(() => {
    coinPopups.delete(popup.id)
    updateRewardPopupLayer()
  }, reduced ? 450 : 1150)
  coinPopups.set(popup.id, popup)
  updateRewardPopupLayer()
}

function clearCoinRecords({ clearPopups = true } = {}) {
  Array.from(coinRecords.keys()).forEach(removeCoinRecord)
  if (clearPopups) clearCoinPopups()
}

function syncCoinRecords() {
  if (!engine || !modelTemplates) return
  const taskKey = String(props.mission?.taskId || props.mission?.simulationId || '')
  const status = String(props.mission?.state || props.mission?.status || '').toUpperCase()
  const replaying = props.timeMode === 'REPLAY'
  const active = ['RUNNING', 'QUEUED'].includes(status)
  if (taskKey !== coinTaskKey) {
    clearCoinRecords()
    coinTaskKey = taskKey
    coinSnapshotInitialized = false
    knownCollectedPointIds = new Set()
  }
  const deliveryPoints = Array.isArray(props.mission?.deliveryPoints) ? props.mission.deliveryPoints : []
  const diamonds = Array.isArray(props.mission?.rewardDiamonds) ? props.mission.rewardDiamonds : []
  const points = [...deliveryPoints, ...diamonds]
  if ((!active && !replaying) || !taskKey || !points.length) {
    clearCoinRecords()
    coinSnapshotInitialized = false
    knownCollectedPointIds = new Set()
    return
  }
  const missingAssetIds = [...new Set(points.map(point => String(point.rewardType || '').toUpperCase() === 'DIAMOND' ? DIAMOND_ASSET_ID : COIN_ASSET_ID))]
    .filter(assetId => !modelTemplates.has(assetId))
  if (missingAssetIds.length) {
    Promise.all(missingAssetIds.map(ensureRewardTemplate)).then(() => updateRoutes())
    return
  }
  const transactions = props.mission?.economy?.recentTransactions || []
  const rewardTransactions = new Map(transactions
    .filter(item => ['DELIVERY_REWARD', 'DIAMOND_REWARD'].includes(item.entryType))
    .map(item => [String(item.referenceId), item]))
  let collected
  if (replaying) {
    const replayTimeMs = replaySimulationTimeMs()
    collected = new Set(Array.from(rewardTransactions.entries())
      .filter(([, transaction]) => Number(transaction.simulationTimeMs || 0) <= replayTimeMs)
      .map(([pointId]) => pointId))
  } else {
    collected = new Set([
      ...(props.mission?.economy?.collectedDeliveryPointIds || []),
      ...(props.mission?.economy?.collectedDiamondIds || []),
      ...(props.mission?.economy?.forfeitedDiamondIds || [])
    ].map(String))
  }
  if (!replaying && coinSnapshotInitialized) {
    collected.forEach(pointId => {
      if (knownCollectedPointIds.has(pointId)) return
      const record = coinRecords.get(`${taskKey}:${pointId}`)
      triggerCoinPickup(record, rewardTransactions.get(pointId)?.amountMinor)
    })
  }
  const desired = new Set()
  points.forEach(point => {
    const pointId = String(point.id || '')
    const key = `${taskKey}:${pointId}`
    if (!pointId || collected.has(pointId)) {
      if (coinRecords.get(key)?.pickupAt) desired.add(key)
      return
    }
    desired.add(key)
    if (!coinRecords.has(key)) createCoinRecord(point, key)
  })
  coinRecords.forEach((record, key) => {
    if (!desired.has(key) && !record.pickupAt) removeCoinRecord(key)
  })
  if (!replaying) {
    knownCollectedPointIds = collected
    coinSnapshotInitialized = true
  }
}

function createModelRecord(device, coordinate) {
  const assignment = modelAssignmentForDevice(device)
  const { role: type, assetId, independentAir } = assignment
  if (!modelTemplates?.has(assetId)) {
    ensureModelTemplate(assetId, type).then(() => updateRoutes())
    return null
  }
  const group = new THREE.Group()
  const modelPresentation = presentationForAsset(assetId, type)
  const template = modelTemplates.get(assetId)
  const object = template ? (template.skinned ? cloneSkeleton(template.scene) : template.scene.clone(true)) : fallbackModel(type)
  if (template) { cloneInstanceMaterials(object); object.scale.setScalar(template.normalizedScale) }
  object.rotation.x = Math.PI / 2
  object.updateMatrixWorld(true)
  const groundedBox = new THREE.Box3().setFromObject(object)
  // Ground vehicles use the route altitude as the road surface. An aircraft
  // route, however, describes the centre of the flying body. Grounding an air
  // model puts its whole body above that anchor, which appears as a large
  // lateral offset from the route in a pitched camera.
  const anchorHeight = type === 'smart_drone'
    ? groundedBox.getCenter(new THREE.Vector3()).z
    : groundedBox.min.z
  object.position.z -= anchorHeight
  group.add(object); engine.add(group)
  const effects = createModelEffects(type, device.deviceId, coordinate)
  const vehicleBeacon = type === 'ground_vehicle' && modelPresentation.warningBeacon ? addVehicleBeacon(group, groundedBox.max.z - groundedBox.min.z) : null
  const vehicleDock = type === 'ground_vehicle' && modelPresentation.visibleDroneDock ? addVehicleDroneDock(group, modelPresentation.compatibilityDock) : null
  const dockVisualBottom = type === 'smart_drone' ? primaryMeshBottom(object) : 0
  group.traverse(node => {
    if (node.isMesh) node.addEventListener('click', event => selectDeviceForFollow(device.deviceId, event))
  })
  let mixer = null, animationAction = null
  if (type === 'smart_drone' && template?.animations?.length) {
    const clip = template.animations.find(item => String(item.name).toLowerCase() === 'hover') || template.animations[0]
    mixer = new THREE.AnimationMixer(object)
    animationAction = mixer.clipAction(clip); animationAction.play(); animationAction.paused = true
  }
  const record = {
    group, object, type, assetId, independentAir, modelPresentation, current: coordinate.slice(), from: coordinate.slice(), to: coordinate.slice(),
    startedAt: performance.now(), lastTargetAt: performance.now(), duration: 1150, currentProgress: 0, fromProgress: 0, toProgress: 0,
    sampler: null, heading: 0, currentHeading: 0, emphasized: true, selected: false, phase: 'DOCKED',
    phaseChangedAt: performance.now(),
    forwardAxis: modelPresentation.forwardAxis, displayScale: 1, lastFrameAt: performance.now(),
    mixer, animationAction, effects, vehicleBeacon, vehicleDock, dockVisualBottom, deliveryActive: false
  }
  modelRecords.set(device.deviceId, record)
  return record
}

function applyModelEmphasis(record, emphasized, selected) {
  if (record.emphasized === emphasized && record.selected === selected) return
  record.emphasized = emphasized; record.selected = selected
  record.object.traverse(node => {
    if (!node.isMesh || !node.material) return
    const materials = Array.isArray(node.material) ? node.material : [node.material]
    materials.forEach(material => {
      const baseOpacity = Number(material.userData.baseOpacity ?? material.opacity ?? 1)
      const baseEmissive = Number(material.userData.baseEmissiveIntensity ?? material.emissiveIntensity ?? 0)
      material.transparent = Boolean(material.userData.baseTransparent) || !emphasized
      material.opacity = baseOpacity * (emphasized ? 1 : .34)
      material.emissiveIntensity = baseEmissive * (selected ? 1.2 : emphasized ? 1 : .32)
      material.needsUpdate = true
    })
  })
  const color = selected ? ROLE_COLORS.selected : ROLE_COLORS[record.type]
  record.effects.halo.material.color.copy(color)
  record.effects.groundRing.material.color.copy(color)
  record.effects.altitudeLine.material.color.copy(color)
}

function updateModelTarget(device, state, emphasized) {
  const coordinate = state.coordinate
  let record = modelRecords.get(device.deviceId)
  const assignment = modelAssignmentForDevice(device)
  if (record && record.assetId !== assignment.assetId) {
    removeModelRecord(device.deviceId)
    record = null
  }
  if (!record) {
    record = createModelRecord(device, coordinate)
    if (!record) return
    record.currentProgress = Number(state.progress || 0)
    record.fromProgress = record.currentProgress
    record.toProgress = record.currentProgress
    record.sampler = state.sampler || null
    record.heading = Number(state.heading || 0)
    record.currentHeading = record.heading
    record.phase = String(state.phase || 'DOCKED').toUpperCase()
    record.deliveryActive = state.deliveryActive === true
    record.phaseChangedAt = performance.now()
    applyModelEmphasis(record, emphasized, device.deviceId === props.selectedId || device.deviceId === followingId.value)
    return
  }
  const now = performance.now()
  const changed = record.to.some((value, index) => Math.abs(value - coordinate[index]) > 1e-7)
  const progressChanged = state.sampler && Math.abs(Number(state.progress) - Number(record.toProgress)) > .0001
  if (changed || progressChanged) {
    if (state.sampler && Number(state.progress) + .001 < Number(record.currentProgress)) {
      record.currentProgress = Number(state.progress || 0)
      record.fromProgress = record.currentProgress
      record.toProgress = record.currentProgress
      record.current = coordinate.slice()
    }
    record.from = record.current.slice(); record.to = coordinate.slice()
    record.fromProgress = record.currentProgress; record.toProgress = Number(state.progress || 0)
    record.startedAt = now
    record.duration = isReplay.value ? 120 : telemetryTransitionDuration(record.lastTargetAt, now)
    record.lastTargetAt = now
  }
  record.sampler = state.sampler || null
  record.heading = Number(state.heading ?? latestMetrics.value.get(device.deviceId)?.direction ?? 0)
  const nextPhase = String(state.phase || record.phase || 'DOCKED').toUpperCase()
  if (nextPhase !== record.phase) record.phaseChangedAt = now
  record.phase = nextPhase
  record.deliveryActive = state.deliveryActive === true
  applyModelEmphasis(record, emphasized, device.deviceId === props.selectedId || device.deviceId === followingId.value)
}
function removeModelRecord(id) {
  const record = modelRecords.get(id)
  if (record) {
    record.mixer?.stopAllAction()
    engine?.remove(record.group); engine?.remove(record.effects.halo); engine?.remove(record.effects.groundRing); engine?.remove(record.effects.altitudeLine)
    if (record.effects.scanRange) engine?.remove(record.effects.scanRange)
    record.object.traverse(node => {
      if (!node.isMesh) return
      const materials = Array.isArray(node.material) ? node.material : [node.material]
      materials.filter(Boolean).forEach(material => material.dispose())
      if (node.userData.instanceOwnedGeometry) node.geometry?.dispose()
    })
    record.effects.halo.material.dispose(); record.effects.groundRing.geometry.dispose(); record.effects.groundRing.material.dispose()
    record.effects.altitudeLine.geometry.dispose(); record.effects.altitudeLine.material.dispose()
    record.effects.scanRange?.geometry?.dispose?.(); record.effects.scanRange?.material?.dispose?.()
    record.vehicleDock?.deck.geometry.dispose(); record.vehicleDock?.ring.geometry.dispose()
    record.vehicleDock?.deckMaterial.dispose(); record.vehicleDock?.ringMaterial.dispose()
  }
  modelRecords.delete(id)
}
function animateCoins(now) {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  coinRecords.forEach((record, key) => {
    const pickupRatio = record.pickupAt ? Math.min(1, (now - record.pickupAt) / record.pickupDuration) : 0
    if (record.pickupAt && pickupRatio >= 1) { removeCoinRecord(key); return }
    const actor = modelRecords.get(String(record.point.actorId || ''))
    const baseCoordinate = [
      record.coordinate[0], record.coordinate[1],
      record.coordinate[2] + (record.point.kind === 'AIR' ? 0 : 1.15)
    ]
    const projected = engine.map.projectArrayCoordinate(baseCoordinate, [])
    record.group.position.fromArray(projected)
    const bobAmplitude = reduced ? 0 : record.point.kind === 'AIR' ? .28 : .35
    const bob = Math.sin(now * .0022 + record.createdAt * .0007) * bobAmplitude
    const pickupLift = record.pickupAt && !reduced ? pickupRatio * 1.4 : 0
    record.spinRoot.position.z = bob + pickupLift
    const cameraDistance = Math.max(1, engine.camera.position.distanceTo(record.group.position))
    const worldUnitsPerPixel = 2 * cameraDistance * Math.tan(THREE.MathUtils.degToRad(engine.camera.fov) / 2) / Math.max(1, mapEl.value?.clientHeight || 1)
    const worldSize = Math.min(record.worldCap, Math.max(.55, worldUnitsPerPixel * record.targetPixels))
    const actorDistance = actor ? actor.group.position.distanceTo(record.group.position) : Infinity
    const near = actorDistance <= Number(record.point.triggerRadiusMeters || (record.point.kind === 'AIR' ? 20 : 14)) * 1.8
    const spinBoost = !reduced && near ? 1.35 : 1
    const pickupSpin = record.pickupAt && !reduced ? 1 + pickupRatio * 5 : 1
    const spinSpeed = record.isDiamond ? DIAMOND_SPIN_RADIANS_PER_SECOND : COIN_SPIN_RADIANS_PER_SECOND
    if (!reduced) record.spinRoot.rotation.z += Math.min(.05, Math.max(0, (now - record.lastFrameAt) / 1000)) * spinSpeed * spinBoost * pickupSpin
    record.lastFrameAt = now
    const pickupScale = record.pickupAt
      ? (reduced ? 1 - pickupRatio : (1 + Math.sin(Math.PI * pickupRatio) * .22) * Math.pow(1 - pickupRatio, .72))
      : 1
    record.spinRoot.scale.setScalar(worldSize * pickupScale)
    const opacity = record.pickupAt ? Math.max(0, 1 - pickupRatio) : 1
    record.materials.forEach(material => {
      material.opacity = Number(material.userData.rewardBaseOpacity ?? 1) * opacity
    })
    record.halo.position.copy(record.group.position); record.halo.position.z += bob + pickupLift
    record.halo.scale.setScalar(Math.min(record.worldCap * (record.isDiamond ? 2.65 : 2.15), worldUnitsPerPixel * (record.targetPixels + (record.isDiamond ? 46 : 30))) * (record.pickupAt ? 1 + pickupRatio * .45 : 1))
    record.halo.material.opacity = (record.isDiamond ? .68 : record.point.kind === 'AIR' ? .34 : .46) * (near && !reduced ? 1.35 : 1) * opacity
    const ringCoordinate = [record.coordinate[0], record.coordinate[1], record.point.kind === 'AIR' ? record.coordinate[2] : .42]
    record.ring.position.fromArray(engine.map.projectArrayCoordinate(ringCoordinate, []))
    const ringSize = Math.min(record.worldCap * .75, Math.max(.7, worldUnitsPerPixel * (record.targetPixels * .55)))
    const ringPulse = reduced ? 1 : 1 + Math.sin(now * .003 + record.createdAt) * .06
    record.ring.scale.setScalar(ringSize * ringPulse)
    record.ring.material.opacity = (record.isDiamond ? .25 : record.point.kind === 'AIR' ? .12 : .26) * (near && !reduced ? 1.35 : 1) * opacity
  })
}
function updateModels() {
  const now = performance.now()
  animateAirspace(now)
  syncTrafficLightNodes()
  syncTrafficLightVisibility()
  modelRecords.forEach((record, id) => {
    const deltaSeconds = Math.min(.05, Math.max(0, (now - record.lastFrameAt) / 1000)); record.lastFrameAt = now
    const ratio = telemetryMotionRatio(now - record.startedAt, record.duration)
    const previousCoordinate = record.current.slice()
    if (record.sampler) {
      record.currentProgress = record.fromProgress + (record.toProgress - record.fromProgress) * ratio
      const sampled = record.sampler.locate(record.currentProgress)
      record.current = sampled.coordinate
      record.heading = sampled.heading
    } else {
      record.current = record.from.map((value, index) => value + (record.to[index] - value) * ratio)
    }
    // The rendered displacement is the final source of truth for orientation.
    // This remains correct for every future route and for telemetry that carries
    // a stale, missing, or differently-conventioned direction field.
    record.heading = movementHeadingDegrees(previousCoordinate, record.current, record.heading)
    record.group.position.fromArray(engine.map.projectArrayCoordinate(record.current, []))
    record.currentHeading = interpolateHeading(record.currentHeading, record.heading, Math.min(.18, .06 + ratio * .12))
    const yaw = modelYawRadians(record.forwardAxis, record.currentHeading)
    const targetQuaternion = new THREE.Quaternion().setFromAxisAngle(MAP_UP, yaw)
    record.group.quaternion.slerp(targetQuaternion, .18)
    const basePhaseState = missionModelPhaseState(record.type, record.phase)
    const phaseState = record.independentAir
      ? { ...basePhaseState, airborne: true, docked: false, rotorActive: true, showGroundRing: true, showHalo: true }
      : basePhaseState
    if (record.animationAction) {
      record.animationAction.paused = !phaseState.rotorActive
      if (phaseState.rotorActive) record.mixer.update(deltaSeconds)
      else if (record.animationAction.time !== 0) { record.animationAction.time = 0; record.mixer.setTime(0) }
    }
    const cameraDistance = Math.max(1, engine.camera.position.distanceTo(record.group.position))
    const takeoffProgress = record.phase === 'TAKEOFF'
      ? Math.min(1, Math.max(0, (now - record.phaseChangedAt) / 4200))
      : phaseState.airborne ? 1 : 0
    const targetScale = modelVisibilityScale(record.type, cameraDistance, engine.camera.fov, mapEl.value?.clientHeight, {
      selected: record.selected,
      phase: record.phase,
      takeoffProgress,
      physicalSize: record.modelPresentation.size,
      minimumScale: record.independentAir ? 1 : undefined
    })
    record.displayScale = THREE.MathUtils.lerp(record.displayScale, targetScale, .14)
    record.group.scale.setScalar(record.displayScale)
    const worldUnitsPerPixel = 2 * cameraDistance * Math.tan(THREE.MathUtils.degToRad(engine.camera.fov) / 2) / Math.max(1, mapEl.value?.clientHeight || 1)
    const roleOpacity = record.emphasized ? 1 : .3
    const haloPixels = record.selected ? 36 : record.type === 'smart_drone' ? 30 : 28
    record.effects.halo.position.copy(record.group.position)
    record.effects.halo.scale.setScalar(worldUnitsPerPixel * haloPixels)
    record.effects.halo.material.opacity = (record.selected ? .5 : hasSimulation.value ? .32 : .18) * roleOpacity
    record.effects.halo.visible = phaseState.showHalo
    const groundPosition = engine.map.projectArrayCoordinate([record.current[0], record.current[1], .38], [])
    record.effects.groundRing.position.fromArray(groundPosition)
    const ringRadius = worldUnitsPerPixel * (record.selected ? 17 : record.type === 'smart_drone' ? 13 : 12)
    const pulse = 1 + Math.sin(now * .003 + (record.type === 'smart_drone' ? 1.3 : 0)) * .09
    record.effects.groundRing.scale.setScalar(ringRadius * pulse)
    record.effects.groundRing.material.opacity = (record.selected ? .72 : hasSimulation.value ? .42 : .24) * roleOpacity
    record.effects.groundRing.visible = phaseState.showGroundRing
    if (record.effects.scanRange) {
      // Use the aircraft's interpolated position in this same frame. Updating
      // the effect object (instead of its telemetry data source) prevents the
      // scan range from jumping one sample ahead of the model.
      record.effects.scanRange.position.fromArray(groundPosition)
      record.effects.scanRange.visible = layers.scan && hasSimulation.value && record.deliveryActive === true
    }
    const altitudePositions = record.effects.altitudeLine.geometry.attributes.position
    altitudePositions.setXYZ(0, groundPosition[0], groundPosition[1], groundPosition[2])
    altitudePositions.setXYZ(1, record.group.position.x, record.group.position.y, record.group.position.z)
    altitudePositions.needsUpdate = true; record.effects.altitudeLine.computeLineDistances()
    record.effects.altitudeLine.material.opacity = 0
    record.effects.altitudeLine.visible = false
    if (record.vehicleBeacon) {
      const beaconWave = (Math.sin(now * .009) + 1) / 2
      record.vehicleBeacon.cyan.opacity = .36 + beaconWave * .62
      record.vehicleBeacon.amber.opacity = .36 + (1 - beaconWave) * .62
    }
  })
  // Baseline logistics tasks keep the carried UAV in a compatibility pose relative
  // to the current ground model. No visible pad is added to the tricycle and
  // this behavior does not claim a player-owned Drone Bay capability.
  modelRecords.forEach((record, id) => {
    if (record.type !== 'smart_drone' || record.independentAir || !missionModelPhaseState(record.type, record.phase).docked) return
    const pair = (props.mission.pairs || []).find(item => item.droneId === id)
    const carrier = pair ? modelRecords.get(pair.vehicleId) : null
    if (!carrier) return
    const dock = carrier.modelPresentation.compatibilityDock
    if (!dock) return
    const dockOffset = new THREE.Vector3(dock.x, dock.y, dock.z)
      .multiplyScalar(carrier.displayScale)
      .applyQuaternion(carrier.group.quaternion)
    record.group.position.copy(carrier.group.position).add(dockOffset)
    record.group.position.z -= record.dockVisualBottom * record.displayScale
  })
  animateCoins(now)
  const followed = modelRecords.get(followingId.value)
  if (followed && !mapDragging) {
    const camera = missionFollowCamera(followed.type, followed.currentHeading, followMode.value)
    engine.map.lookAt(followed.current, { ...camera, range: camera.range * followZoomScale })
  }
}

const AIRSPACE_PALETTE = {
  ABSOLUTE_NO_FLY: '#ff3f64', TEMPORARY_NO_FLY: '#ff7b3d', DANGER_AIRSPACE: '#ff234f',
  RISK_AIRSPACE: '#ffd166', ALTITUDE_RESTRICTED: '#816dff', ALTITUDE_CORRIDOR: '#9a72ff'
}
const THREAT_OPACITY = { NORMAL: .1, NEAR: .25, CONFLICT: .5, IMMINENT: .8, VIOLATION: 1 }
// The Baidu 3D basemap writes buildings and terrain into the shared depth
// buffer before our mission layer. Airspace is operational information rather
// than world geometry, so it must remain readable through those buildings.
// Keeping depth writes disabled also lets overlapping volumes stay translucent.
const AIRSPACE_RENDER_ORDER = 30
function disposeAirspaceVisual(visual) {
  if (!visual) return
  if (visual.projection) engine?.remove(visual.projection)
  engine?.remove(visual.group)
  visual.group.traverse(node => { node.geometry?.dispose?.(); if (Array.isArray(node.material)) node.material.forEach(material => material.dispose?.()); else node.material?.dispose?.() })
}
function localProjectedRing(volume, altitude) {
  const source = coordinates(volume.footprint, altitude)
  const points = source.length > 1 && source[0][0] === source.at(-1)[0] && source[0][1] === source.at(-1)[1] ? source.slice(0, -1) : source
  const projected = points.map(point => engine.map.projectArrayCoordinate([point[0], point[1], altitude], []))
  if (projected.length < 3) return null
  const origin = projected.reduce((sum, point) => sum.add(new THREE.Vector3(...point)), new THREE.Vector3()).multiplyScalar(1 / projected.length)
  return { origin, points: projected.map(point => new THREE.Vector3(...point).sub(origin)) }
}
function createAirspaceVisual(volume) {
  const floor = Number(volume.floorMeters || 0), ceiling = Number(volume.ceilingMeters || 80)
  const lower = localProjectedRing(volume, floor), upper = localProjectedRing(volume, ceiling)
  if (!lower || !upper) return null
  const color = new THREE.Color(AIRSPACE_PALETTE[volume.ruleType] || '#ff5d7d')
  const projection = engine.add(new mapvthree.Polygon({
    color: `#${color.getHexString()}`,
    emissive: `#${color.getHexString()}`,
    opacity: .18,
    transparent: true,
    extrude: false,
    zOffset: .45,
    depthTest: false,
    depthWrite: false
  }))
  projection.renderOrder = AIRSPACE_RENDER_ORDER
  projection.dataSource = noFlyZoneSource([volume.footprint])
  const group = new THREE.Group(); group.position.copy(lower.origin)
  const shape = new THREE.Shape(lower.points.map(point => new THREE.Vector2(point.x, point.y)))
  const ground = new THREE.Mesh(new THREE.ShapeGeometry(shape), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .14, side: THREE.DoubleSide, depthTest: false, depthWrite: false }))
  ground.position.z = lower.points[0].z; ground.renderOrder = AIRSPACE_RENDER_ORDER + 1
  const wallPositions = []
  const sideLinePositions = []
  for (let index = 0; index < lower.points.length; index += 1) {
    const next = (index + 1) % lower.points.length, a = lower.points[index], b = lower.points[next]
    const c = upper.points[next].clone().add(upper.origin).sub(lower.origin), d = upper.points[index].clone().add(upper.origin).sub(lower.origin)
    wallPositions.push(...a.toArray(), ...b.toArray(), ...c.toArray(), ...a.toArray(), ...c.toArray(), ...d.toArray())
    sideLinePositions.push(...a.toArray(), ...d.toArray())
  }
  const walls = new THREE.Mesh(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(wallPositions, 3)), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .18, side: THREE.DoubleSide, depthTest: false, depthWrite: false, blending: THREE.AdditiveBlending }))
  walls.renderOrder = AIRSPACE_RENDER_ORDER + 2
  const lowerLine = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(lower.points), new THREE.LineBasicMaterial({ color, transparent: true, opacity: .8, depthTest: false, depthWrite: false }))
  const upperLocal = upper.points.map(point => point.clone().add(upper.origin).sub(lower.origin))
  const upperLine = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(upperLocal), new THREE.LineDashedMaterial({ color, transparent: true, opacity: .58, dashSize: 8, gapSize: 5, depthTest: false, depthWrite: false }))
  upperLine.computeLineDistances(); lowerLine.renderOrder = AIRSPACE_RENDER_ORDER + 4; upperLine.renderOrder = AIRSPACE_RENDER_ORDER + 4
  const sideLines = new THREE.LineSegments(new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(sideLinePositions, 3)), new THREE.LineDashedMaterial({ color, transparent: true, opacity: .46, dashSize: 7, gapSize: 5, depthTest: false, depthWrite: false }))
  sideLines.computeLineDistances(); sideLines.renderOrder = AIRSPACE_RENDER_ORDER + 4
  const scan = new THREE.Mesh(new THREE.ShapeGeometry(shape), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .08, side: THREE.DoubleSide, depthTest: false, depthWrite: false, blending: THREE.AdditiveBlending }))
  scan.renderOrder = AIRSPACE_RENDER_ORDER + 3
  ;[ground, walls, lowerLine, upperLine, sideLines, scan].forEach(object => { object.frustumCulled = false })
  group.add(ground, walls, lowerLine, upperLine, sideLines, scan); engine.add(group)
  const visual = { group, projection, ground, walls, lowerLine, upperLine, sideLines, scan, volume, height: upperLocal[0].z - lower.points[0].z }
  airspaceVisuals.set(String(volume.id), visual)
  return visual
}
function updateAirspaceVisuals() {
  if (!engine) return
  const volumes = airspaceVolumes()
  const visualVolumes = volumes.flatMap(volume => volume.ruleType === 'ALTITUDE_CORRIDOR'
    ? [
        { ...volume, id: `${volume.id}::LOWER`, logicalVolumeId: volume.id, floorMeters: volume.floorMeters ?? 0, ceilingMeters: volume.corridorFloorMeters },
        { ...volume, id: `${volume.id}::UPPER`, logicalVolumeId: volume.id, floorMeters: volume.corridorCeilingMeters, ceilingMeters: volume.ceilingMeters ?? 120 }
      ]
    : [{ ...volume, logicalVolumeId: volume.id }])
  const ids = new Set(visualVolumes.map(volume => String(volume.id)))
  const structure = visualVolumes.map(volume => `${volume.id}:${JSON.stringify(volume.footprint)}:${volume.floorMeters}:${volume.ceilingMeters}`).join('|')
  if (structure !== airspaceStructureSignature) {
    airspaceVisuals.forEach(disposeAirspaceVisual); airspaceVisuals.clear()
    visualVolumes.forEach(createAirspaceVisual); airspaceStructureSignature = structure
  }
  airspaceVisuals.forEach((visual, id) => { if (!ids.has(id)) { disposeAirspaceVisual(visual); airspaceVisuals.delete(id) } })
  visualVolumes.forEach(volume => {
    const visual = airspaceVisuals.get(String(volume.id)); if (!visual) return
    visual.volume = volume
    const threat = String(volume.threatLevel || 'NORMAL'), base = THREAT_OPACITY[threat] ?? .1
    const planning = props.planningPreview || volume.planningPreview === true
    const activation = resolveAirspaceActivation(volume, { planningPreview: planning })
    const selected = String(props.selectedAirspaceId || '') === String(volume.logicalVolumeId || volume.id)
    const visible = layers.airspace && (planning || !['SCHEDULED', 'EXPIRED'].includes(String(volume.state || 'ACTIVE'))) && activation > 0
    visual.group.visible = visible
    visual.projection.visible = visible
    const blocking = volume.blocking !== false
    visual.projection.opacity = (planning ? (blocking ? .24 : .18) : Math.min(.32, .1 + base * .16 + (selected ? .04 : 0))) * activation
    visual.group.scale.set(activation, activation, 1)
    visual.ground.material.opacity = (planning ? (blocking ? .22 : .16) : Math.min(.38, .12 + base * .18 + (selected ? .05 : 0))) * activation
    visual.walls.material.opacity = (planning ? (blocking ? .28 : .2) : Math.min(.42, .14 + base * .2 + (selected ? .06 : 0))) * activation
    visual.lowerLine.material.opacity = planning ? 1 : Math.min(1, .58 + base * .34 + (selected ? .16 : 0))
    visual.upperLine.material.opacity = planning ? .9 : Math.min(.94, .42 + base * .34 + (selected ? .1 : 0))
    visual.sideLines.material.opacity = planning ? .78 : Math.min(.82, .34 + base * .34 + (selected ? .1 : 0))
  })
  const visibleVolumes = volumes.filter(volume => !['SCHEDULED', 'EXPIRED'].includes(String(volume.state || 'ACTIVE')))
  const labelSignature = visibleVolumes.map(volume => `${volume.id}:${volume.state}:${volume.threatLevel}:${volume.planningPreview}:${volume.ceilingMeters}:${volume.corridorFloorMeters}:${volume.corridorCeilingMeters}:${Math.round(Number(volume.currentAltitudeMeters || 0))}:${volume.targetAltitudeMeters}`).join('|')
  if (airspaceLabelLayer && labelSignature !== airspaceLabelSignature) { airspaceLabelLayer.dataSource = airspaceLabelSource(visibleVolumes); airspaceLabelSignature = labelSignature }
  if (airspaceLabelLayer) airspaceLabelLayer.visible = layers.airspace && visibleVolumes.length > 0
  const sourceConflicts = Array.isArray(props.mission?.airspace?.conflicts) ? props.mission.airspace.conflicts : []
  const conflicts = visibleAirspaceConflicts(sourceConflicts, volumes, props.tutorialRedConflictLocked)
  const nextConflictSignature = conflicts.map(conflict => `${conflict.id}:${conflict.distanceMeters}:${conflict.estimatedEntrySeconds}:${conflict.threatLevel}:${conflict.predictive}:${conflict.currentlyActive}`).join('|')
  if (conflictLabelLayer && nextConflictSignature !== conflictLabelSignature) { conflictLabelLayer.dataSource = conflictLabelSource(conflicts); conflictLabelSignature = nextConflictSignature }
  if (conflictLabelLayer) conflictLabelLayer.visible = layers.airspace && conflicts.length > 0
}
function animateAirspace(now) {
  airspaceVisuals.forEach(visual => {
    if (!visual.group.visible) return
    const floorZ = visual.ground.position.z
    visual.scan.position.z = floorZ + visual.height * ((now / 5200) % 1)
    const pulse = ['IMMINENT', 'VIOLATION'].includes(String(visual.volume.threatLevel)) ? .55 + Math.sin(now * .009) * .25 : .08
    visual.scan.material.opacity = Math.max(.03, pulse)
  })
}
function createRouteVisual(route) {
  const air = route.kind === 'AIR'
  // MapV Three 1.6.x 的 Polyline 材质没有公开动画参数代理；把这些
  // 参数交给构造器或对象代理都会落到 THREE.ShaderMaterial 并产生
  // unknown-property 警告，因此路线只使用当前版本稳定支持的样式。
  const planned = engine.add(new mapvthree.Polyline({
    flat: false,
    color: air ? '#a989ff' : '#46dff2',
    emissive: air ? '#7b4dff' : '#00a9d4',
    lineWidth: air ? 2.5 : 2,
    opacity: .48,
    dashed: true,
    dashArray: air ? 34 : 26,
    dashRatio: .55
  }))
  planned.height = air ? 0 : .1

  const actual = engine.add(new mapvthree.Polyline({
    flat: false,
    color: air ? '#ef69ff' : '#37f3cf',
    emissive: air ? '#b23cff' : '#00e9ff',
    lineWidth: air ? 3 : 3,
    opacity: .96
  }))
  const conflict = engine.add(new mapvthree.Polyline({ flat: false, color: '#ff3f64', emissive: '#ff183f', lineWidth: 5, opacity: .95 }))
  actual.height = air ? .15 : .22
  const visual = { planned, actual, conflict, plannedSignature: '', actualSignature: '', conflictSignature: '' }; routeLayers.set(route.deviceId, visual); return visual
}
function coordinateSignature(points = []) {
  return polylineGeometryKey(points)
}
function pointSignature(points = []) {
  return points.map(item => `${item.deviceId}:${item.coordinate.map(value => Number(value).toFixed(6)).join(',')}`).join('|')
}
function updateRoutes() {
  // 设备轮询可能早于 GLB 下载完成。模型模板就绪前不创建记录，避免
  // 将待命设备永久固化成 fallbackModel() 的程序化模型。
  if (!engine || !hitLayer || !modelTemplates) return
  const routeIds = new Set(), latest = new Map(), viewportPoints = [], highlighted = pairDeviceIds()
  ;(props.mission.routes || []).forEach(route => {
    routeIds.add(route.deviceId)
    const visual = routeLayers.get(route.deviceId) || createRouteVisual(route)
    // TaskInstance 中的航点折线既是计划展示，也是仿真内核的执行依据。
    // 不再在浏览器端单独拟合曲线，避免计划线与实际遥测走不同几何路径。
    const planned = routeCoordinates(route, route.effectivePoints || route.points)
    const plannedSignature = coordinateSignature(planned)
    let sampler = routeSamplers.get(route.deviceId)
    // 绕飞路线通常保留原起终点，航点数量也可能与原路线相同；缓存键必须
    // 覆盖全部几何点，否则独立飞行器会继续采样旧路线。
    const samplerKey = plannedSignature
    if (!sampler || sampler.key !== samplerKey) { sampler = Object.assign(createPolylineSampler(planned), { key: samplerKey }); routeSamplers.set(route.deviceId, sampler) }
    const metrics = latestMetrics.value.get(route.deviceId) || {}
    const actualRaw = routeCoordinates(route, visibleActualPoints(route))
    // 百度 JSAPI Three 的 Polyline 直接消费 GeoJSON LineString。实际轨迹保留
    // 遥测点原序列，避免展示性曲线拟合越过真实位置或道路中心线。
    const actual = actualRaw
    const actualSignature = coordinateSignature(actual)
    if (visual.plannedSignature !== plannedSignature) {
      visual.planned.dataSource = lineSource(planned, `${route.deviceId}-planned`)
      visual.plannedSignature = plannedSignature
    }
    if (visual.actualSignature !== actualSignature) {
      visual.actual.dataSource = lineSource(actual, `${route.deviceId}-actual`)
      visual.actualSignature = actualSignature
    }
    const routeConflicts = route.kind === 'AIR' ? (props.mission?.airspace?.conflicts || []).filter(item => String(item.actorId || '') === String(route.deviceId)) : []
    const conflictSegments = []
    if (routeConflicts.length && planned.length > 1) {
      const conflictSampler = createPolylineSampler(planned)
      routeConflicts.forEach(routeConflict => {
        const segment = []
        const start = Number(routeConflict.startRouteProgress || 0) / 100, end = Number(routeConflict.endRouteProgress || start) / 100
        for (let step = 0; step <= 28; step += 1) segment.push(conflictSampler.locate(start + (end - start) * step / 28).coordinate)
        conflictSegments.push(segment)
      })
    }
    if (routeConflicts.length) {
      const conflictColor = AIRSPACE_PALETTE[routeConflicts[0].ruleType] || '#ff3f64'
      visual.conflict.color = conflictColor; visual.conflict.emissive = conflictColor
    }
    const conflictSignature = conflictSegments.map(coordinateSignature).join('||')
    if (visual.conflictSignature !== conflictSignature) { visual.conflict.dataSource = multiLineSource(conflictSegments, `${route.deviceId}-airspace-conflict`); visual.conflictSignature = conflictSignature }
    visual.conflict.visible = layers.airspace && conflictSegments.length > 0
    visual.planned.visible = layers.planned && planned.length > 1; visual.actual.visible = hasSimulation.value && layers.actual && actual.length > 1
    const emphasized = !highlighted.size || highlighted.has(route.deviceId); visual.planned.opacity = emphasized ? .5 : .16; visual.actual.opacity = emphasized ? .98 : .28
    const routeDevice = props.devices.find(device => device.deviceId === route.deviceId)
    const independentAir = route.kind === 'AIR'
      && routeDevice
      && modelAssignmentForDevice(routeDevice).independentAir
    const rawRouteProgress = Math.max(0, Math.min(1, Number(metrics.routeProgress || 0) / 100))
    // Dynamic task sorties reserve 0..5% and 95..100% for take-off/landing.
    // Independent VTOL rendering samples the executable route, so normalize the
    // server's sortie progress before looking up the position on that polyline.
    const routeProgress = route.kind === 'AIR'
      ? executableAirRouteFraction(rawRouteProgress, Boolean(props.mission?.taskId))
      : rawRouteProgress
    // VTOL is a self-contained aircraft. Baseline task telemetry can still report
    // the paired vehicle position while the original UAV is carried or recovered,
    // so an independent aircraft always derives its map pose from its own frozen
    // air route and the server-reported air-route progress.
    const independentRouteState = independentAir ? sampler.locate(routeProgress) : null
    const coordinate = independentRouteState?.coordinate || (actual.length ? actual.at(-1) : planned[0])
    const telemetryHeading = Number(metrics.direction)
    const routeHeading = planned.length > 1 ? sampler.locate(routeProgress).heading : null
    const fallbackHeading = Number.isFinite(routeHeading)
      ? routeHeading
      : Number.isFinite(telemetryHeading) ? telemetryHeading : 0
    // Prefer the direction in which the rendered actor actually moved. At a
    // standstill or before the first telemetry step, use the local route tangent.
    const heading = independentRouteState?.heading ?? (actual.length > 1
      ? movementHeadingDegrees(actual.at(-2), actual.at(-1), fallbackHeading)
      : fallbackHeading)
    const phase = metrics.missionPhase || (!hasSimulation.value ? 'DOCKED' : props.mission.missionPhase)
    latest.set(route.deviceId, {
      coordinate,
      heading,
      progress: routeProgress,
      sampler: independentAir ? sampler : null,
      phase,
      deliveryActive: metrics.deliveryActive === true
    })
    viewportPoints.push(...planned)
  })
  routeLayers.forEach((visual, id) => { if (!routeIds.has(id)) { engine.remove(visual.planned); engine.remove(visual.actual); engine.remove(visual.conflict); routeLayers.delete(id) } })
  const deviceIds = new Set(), hitPoints = []
  ;(props.mission.pairs || []).forEach(pair => {
    const vehicle = latest.get(pair.vehicleId), drone = latest.get(pair.droneId)
    if (!vehicle || !drone || !['DEPART', 'DOCKED'].includes(String(drone.phase || '').toUpperCase())) return
    const droneDevice = props.devices.find(device => device.deviceId === pair.droneId)
    if (droneDevice && modelAssignmentForDevice(droneDevice).independentAir) return
    drone.coordinate = vehicle.coordinate.slice()
    drone.heading = vehicle.heading
  })
  props.devices.forEach(device => {
    const state = latest.get(device.deviceId) || { coordinate: [Number(device.longitude), Number(device.latitude), Number(device.altitude || (device.deviceType === 'smart_drone' ? 70 : .3))], heading: Number(device.sensorData?.direction || 0), progress: 0, sampler: null, deliveryActive: device.sensorData?.deliveryActive === true }
    if (!state.coordinate.every(Number.isFinite)) return
    deviceIds.add(device.deviceId); updateModelTarget(device, state, !highlighted.size || highlighted.has(device.deviceId)); hitPoints.push({ deviceId: device.deviceId, coordinate: state.coordinate })
  })
  modelRecords.forEach((_, id) => { if (!deviceIds.has(id)) removeModelRecord(id) })
  const hitSignature = pointSignature(hitPoints)
  if (pointLayerSignatures.hit !== hitSignature) { hitLayer.dataSource = pointSource(hitPoints); pointLayerSignatures.hit = hitSignature }
  if (pointLayerSignatures.pick !== hitSignature) { pickLayer.dataSource = pointSource(hitPoints); pointLayerSignatures.pick = hitSignature }
  const liveLights = Array.isArray(props.mission?.trafficLights) ? props.mission.trafficLights : []
  liveTrafficLights = liveLights
  const nextTrafficStructureSignature = liveLights.map(light => `${light.id}:${light.longitude}:${light.latitude}:${light.movement || ''}:${light.signalType || ''}`).join('|')
  if (trafficLightLayer && nextTrafficStructureSignature !== trafficLightStructureSignature) {
    trafficLightLayer.dataSource = trafficLightSource(liveLights)
    trafficLightStructureSignature = nextTrafficStructureSignature
    trafficLightRenderedStateSignature = ''
    trafficLightRenderedNode = null
  }
  syncTrafficLightNodes()
  if (trafficLightLayer) trafficLightLayer.visible = layers.traffic && liveLights.length > 0
  syncCoinRecords()
  const missionPoints = []
  const deliveryTargets = Array.isArray(props.mission?.deliveryPoints) ? props.mission.deliveryPoints : props.mission?.deliveryTargets || []
  const deliveryNumbers = { GROUND: 0, AIR: 0 }
  deliveryTargets.forEach((target, index) => {
    const kind = target.kind === 'AIR' ? 'AIR' : 'GROUND'
    const ordinal = ++deliveryNumbers[kind]
    const coordinate = coordinates([target.position || target.coordinate], kind === 'AIR' ? 70 : .6)[0]
    const amount = target.rewardMinor ? ` · ¥${Math.round(Number(target.rewardMinor) / 100).toLocaleString('zh-CN')}` : ''
    if (coordinate) missionPoints.push({ id: String(target.id || `target-${index + 1}`), kind: 'TARGET', label: `${kind === 'AIR' ? '无人机配送点' : '车辆配送点'} ${ordinal}${amount}`, coordinate })
  })
  const launchPoint = coordinates([props.mission?.launchPoint], 2.35)[0]
  const recoveryPoint = coordinates([props.mission?.recoveryPoint], 2.35)[0]
  if (launchPoint) missionPoints.push({ id: 'mission-launch', kind: 'LAUNCH', label: '无人机起飞点', coordinate: launchPoint })
  if (recoveryPoint) missionPoints.push({ id: 'mission-recovery', kind: 'RECOVERY', label: '无人机返航点', coordinate: recoveryPoint })
  const nextMissionPointSignature = missionPoints.map(item => `${item.id}:${item.kind}:${item.coordinate.join(',')}`).join('|')
  if (missionPointLayer && missionPointSignature !== nextMissionPointSignature) {
    missionPointLayer.dataSource = missionPointSource(missionPoints)
    missionPointSignature = nextMissionPointSignature
  }
  if (missionPointLayer) missionPointLayer.visible = layers.planned && missionPoints.length > 0
  viewportPoints.push(...missionPoints.map(item => item.coordinate))
  updateAirspaceVisuals()
  airspaceVolumes().forEach(volume => viewportPoints.push(...coordinates(volume.footprint, Number(volume.floorMeters || 0))))
  currentMissionViewportPoints = viewportPoints.map(point => point.slice())
  if (!fitted && viewportPoints.length > 1) {
    const planner = props.planningPreview ? document.querySelector('[data-tutorial-id="mission-planner"]') : null
    const focusPoints = props.planningPreview
      ? plannerAwareViewportPoints(viewportPoints, {
          panelWidth: Number(planner?.getBoundingClientRect().right || 0) + 24,
          viewportWidth: window.innerWidth
        })
      : viewportPoints
    engine.map.setHeading(OVERVIEW.heading)
    engine.map.setPitch(OVERVIEW.pitch)
    engine.map.setViewport(focusPoints, props.planningPreview
      ? planningPreviewViewportOptions()
      : missionViewportOptions())
    fitted = true
  }
  engine.requestRender()
}

async function initMap() {
  const ak = runtimeConfig.baiduMapAk.trim(); if (!ak) throw new Error('未配置百度地图浏览器端 AK'); if (!mapEl.value) return
  window.MAPV_BASE_URL = '/mapvthree/'
  const provider = createBaiduCyberProvider(mapvthree, THREE, ak)
  engine = new mapvthree.Engine(mapEl.value, { map: { projection: 'EPSG:3857', center: OVERVIEW.center, heading: OVERVIEW.heading, pitch: OVERVIEW.pitch, range: OVERVIEW.range, provider: null }, rendering: { sky: null, enableAnimationLoop: true, animationLoopFrameTime: 16, pixelRatio: Math.min(window.devicePixelRatio || 1, 1.6), features: { bloom: { enabled: true, strength: .42, threshold: .61, radius: .42 } } }, widgets: { enabled: false } })
  engine.map.setMinRange(45); engine.map.setMaxRange(18000)
  if (engine.map.control) { engine.map.control.zoomSpeed = .0016; engine.map.control.inertiaZoom = .82 }
  const sky = engine.add(new mapvthree.DefaultSky()); sky.color = new THREE.Color('#0a1c33'); sky.highColor = new THREE.Color('#020817')
  mapView = engine.add(new mapvthree.MapView({ terrainProvider: null, vectorProvider: provider }))
  hitLayer = engine.add(new mapvthree.EffectPoint({ type: 'RadarLayered', color: '#00e9ff', sideColor: '#34f5c5', size: 30, height: 1, duration: 1600, keepSize: true, opacity: .18 }))
  pickLayer = engine.add(new mapvthree.EffectModelPoint({ normalize: true, rotateToZUp: false, keepSize: true, size: 38, height: 1, animationRotate: false }))
  missionPointLayer = engine.add(new mapvthree.DOMPoint({ offset: [-16, -16] }))
  missionPointLayer.renderItem = renderMissionPoint
  rewardPopupLayer = engine.add(new mapvthree.DOMPoint({ offset: [-34, -58] }))
  rewardPopupLayer.renderItem = renderRewardPopup
  rewardPopupLayer.visible = false
  trafficLightLayer = engine.add(new mapvthree.DOMPoint({ offset: [-31, -44] }))
  trafficLightLayer.renderItem = renderTrafficLight
  airspaceLabelLayer = engine.add(new mapvthree.DOMPoint({ offset: [-42, -18] }))
  airspaceLabelLayer.renderItem = renderAirspaceLabel
  conflictLabelLayer = engine.add(new mapvthree.DOMPoint({ offset: [-62, -22] }))
  conflictLabelLayer.renderItem = renderConflictLabel
  pickLayer.model = new THREE.Mesh(
    new THREE.BoxGeometry(1.8, 1.8, 1.8),
    new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false, colorWrite: false })
  )
  pickLayer.addEventListener('click', event => {
    const device = eventDevice(event)
    selectDeviceForFollow(device?.deviceId, event)
  })
  pickLayer.addEventListener('mouseenter', () => { if (mapEl.value) mapEl.value.style.cursor = 'pointer' })
  pickLayer.addEventListener('mouseleave', () => { if (mapEl.value) mapEl.value.style.cursor = '' })
  engine.map.addEventListener('click', event => {
    const deviceId = deviceAtMapPixel(event?.pixel)
    if (deviceId) selectDeviceForFollow(deviceId, event)
  })
  resizeObserver = new ResizeObserver(() => engine?.requestRender()); resizeObserver.observe(mapEl.value)
  modelTemplates = new Map()
  await Promise.all([loadAssignedModelTemplates(), ensureRewardTemplate(COIN_ASSET_ID), ensureRewardTemplate(DIAMOND_ASSET_ID)])
  prepareRenderListener = updateModels; engine.addPrepareRenderListener(prepareRenderListener)
  updateRoutes(); mapLoading.value = false
}
function setFollowMode(mode) { followMode.value = mode === 'side' ? 'side' : 'rear'; engine?.requestRender() }
function leaveFollow() {
  followingId.value = ''
  followZoomScale = 1
  emit('select', '')
  const camera = missionOverviewCamera(currentMissionViewportPoints, {
    heading: OVERVIEW.heading,
    pitch: OVERVIEW.pitch
  })
  if (camera) {
    engine?.map.flyTo(camera.center, {
      heading: camera.heading,
      pitch: camera.pitch,
      range: camera.range,
      duration: 850
    })
  } else {
    engine?.map.flyTo(OVERVIEW.center, {
      heading: OVERVIEW.heading,
      pitch: OVERVIEW.pitch,
      range: OVERVIEW.range,
      duration: 850
    })
  }
}
function onMapPointerDown(event) {
  if (event.button !== undefined && event.button !== 0) return
  mapDragging = true
  if (followingId.value) { followingId.value = ''; followZoomScale = 1 }
  if (mapView) mapView.freezeUpdate = true
}
function remapMapRotationPointerDown(event) {
  if (!event.isTrusted) return
  if (event.button === 2) {
    event.preventDefault()
    event.stopImmediatePropagation()
    return
  }
  if (event.button !== 1) return
  event.preventDefault()
  event.stopImmediatePropagation()
  const target = event.target
  if (!(target instanceof EventTarget)) return
  target.dispatchEvent(new PointerEvent('pointerdown', {
    bubbles: true,
    cancelable: true,
    composed: true,
    pointerId: event.pointerId,
    pointerType: event.pointerType,
    isPrimary: event.isPrimary,
    button: 2,
    buttons: 2,
    clientX: event.clientX,
    clientY: event.clientY,
    screenX: event.screenX,
    screenY: event.screenY,
    ctrlKey: event.ctrlKey,
    shiftKey: event.shiftKey,
    altKey: event.altKey,
    metaKey: event.metaKey
  }))
}
function preventMapContextMenu(event) { event.preventDefault() }
function preventMapAuxiliaryClick(event) { if (event.button === 1) event.preventDefault() }
function onMapPointerUp() {
  if (!mapDragging) return
  mapDragging = false
  if (mapView) mapView.freezeUpdate = false
  engine?.requestRender()
}
function onFollowWheel(event) {
  if (!followingId.value) return
  event.preventDefault()
  event.stopPropagation()
  followZoomScale = adjustFollowZoomScale(followZoomScale, event.deltaY)
  engine?.requestRender()
}
function onKeydown(event) { if (event.key === 'Escape' && followingId.value) leaveFollow() }
watch([() => props.devices, () => props.mission, () => props.selectedId, () => props.selectedAirspaceId, () => props.tutorialRedConflictLocked, layers, replayPercent], updateRoutes, { deep: true })
watch(() => props.modelAssignments, () => { refreshAssignedModels() }, { deep: true })
watch(() => props.mission?.taskId || props.mission?.scenarioTemplateId || props.mission?.simulationId, () => { fitted = false; updateRoutes() })
watch(followingId, deviceId => emit('follow-change', String(deviceId || '')), { immediate: true })
watch(() => props.selectedId, (deviceId) => {
  if (!deviceId) { followingId.value = ''; followZoomScale = 1; return }
  if (props.devices.some(item => item.deviceId === deviceId)) {
    if (followingId.value !== deviceId) { followMode.value = 'rear'; followZoomScale = 1 }
    followingId.value = deviceId
  }
})
watch(() => props.timeCursor, value => {
  if (isReplay.value && props.timeMode === 'REPLAY') replayPercent.value = Math.max(0, Math.min(100, Number(value) || 0))
}, { immediate: true })
watch(isReplay, value => {
  replayPercent.value = value && props.timeMode === 'REPLAY' ? Math.max(0, Math.min(100, Number(props.timeCursor) || 0)) : 100
})
onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pointerup', onMapPointerUp)
  window.addEventListener('pointercancel', onMapPointerUp)
  window.addEventListener('blur', onMapPointerUp)
  mapEl.value?.addEventListener('pointerdown', remapMapRotationPointerDown, { capture: true })
  mapEl.value?.addEventListener('pointerdown', onMapPointerDown)
  mapEl.value?.addEventListener('contextmenu', preventMapContextMenu)
  mapEl.value?.addEventListener('auxclick', preventMapAuxiliaryClick)
  mapEl.value?.addEventListener('wheel', onFollowWheel, { capture: true, passive: false })
  initMap().catch(error => { console.error('[LogisticsMissionMap] 百度 MapV Three 初始化失败：', error); mapLoading.value = false; mapError.value = `${error?.message || '三维地图初始化失败'}。请检查百度浏览器端 AK、Referer 白名单及网络连接。` })
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pointerup', onMapPointerUp)
  window.removeEventListener('pointercancel', onMapPointerUp)
  window.removeEventListener('blur', onMapPointerUp)
  mapEl.value?.removeEventListener('pointerdown', remapMapRotationPointerDown, true)
  mapEl.value?.removeEventListener('pointerdown', onMapPointerDown)
  mapEl.value?.removeEventListener('contextmenu', preventMapContextMenu)
  mapEl.value?.removeEventListener('auxclick', preventMapAuxiliaryClick)
  mapEl.value?.removeEventListener('wheel', onFollowWheel, true)
  resizeObserver?.disconnect()
  if (prepareRenderListener) engine?.removePrepareRenderListener(prepareRenderListener)
  clearCoinRecords()
  Array.from(modelRecords.keys()).forEach(removeModelRecord)
  routeLayers.clear(); routeSamplers.clear(); airspaceVisuals.forEach(disposeAirspaceVisual); airspaceVisuals.clear()
  glowTexture?.dispose(); glowTexture = null
  engine?.dispose(); engine = null; mapView = null; missionPointLayer = null; missionPointSignature = ''; rewardPopupLayer = null; trafficLightLayer = null; trafficLightStructureSignature = ''
  airspaceLabelLayer = null; conflictLabelLayer = null; airspaceStructureSignature = ''; airspaceLabelSignature = ''; conflictLabelSignature = ''
  liveTrafficLights = []; trafficLightRenderedStateSignature = ''; trafficLightRenderedNode = null
  modelTemplates?.clear(); modelTemplateLoads.clear(); modelTemplates = null
  coinTaskKey = ''; coinSnapshotInitialized = false; knownCollectedPointIds = new Set()
  currentMissionViewportPoints = []
  emit('follow-change', '')
})
</script>

<style scoped>
.mission-map { position:relative; width:100%; height:100%; min-height:520px; overflow:hidden; color:var(--text-primary,#dff7ff); background:#071326; isolation:isolate; }
.map-canvas { position:absolute; inset:0; z-index:0; }
.map-canvas :deep(canvas) { display:block; width:100%; height:100%; }
.map-canvas :deep(.traffic-signal-marker) { --lamp:#91a7b5; --lamp-core:#d9e3e8; position:absolute; display:flex; align-items:center; width:62px; height:27px; box-sizing:border-box; padding:3px 5px 3px 4px; border:1px solid rgba(255,255,255,.18); border-radius:7px; opacity:0; visibility:hidden; color:#eef6f8; background:linear-gradient(180deg,rgba(49,55,60,.97),rgba(17,21,24,.98)); box-shadow:0 2px 7px rgba(0,0,0,.64),inset 0 1px 0 rgba(255,255,255,.12); filter:drop-shadow(0 0 4px rgba(0,0,0,.55)); pointer-events:none; transform:translateY(4px) scale(.94); transform-origin:50% 100%; transition:opacity .18s ease,transform .18s ease,visibility 0s linear .18s; }
.map-canvas :deep(.traffic-signal-marker.is-zoom-visible) { opacity:1; visibility:visible; transform:translateY(0) scale(1); transition-delay:0s; }
.map-canvas :deep(.traffic-signal-marker.is-red) { --lamp:#ff263d; --lamp-core:#ffb4bb; }
.map-canvas :deep(.traffic-signal-marker.is-yellow),.map-canvas :deep(.traffic-signal-marker.is-flashing-yellow) { --lamp:#ffc21c; --lamp-core:#fff1a6; }
.map-canvas :deep(.traffic-signal-marker.is-green) { --lamp:#24e26f; --lamp-core:#baffd2; }
.map-canvas :deep(.traffic-signal-lamp) { position:relative; display:grid; flex:0 0 19px; width:19px; height:19px; place-items:center; border:1px solid rgba(0,0,0,.88); border-radius:50%; color:#07100b; background:radial-gradient(circle at 37% 32%,var(--lamp-core) 0 7%,var(--lamp) 30% 62%,color-mix(in srgb,var(--lamp),#000 38%) 100%); box-shadow:0 0 8px color-mix(in srgb,var(--lamp),transparent 34%),0 0 3px var(--lamp),inset 0 -2px 3px rgba(0,0,0,.5); }
.map-canvas :deep(.traffic-signal-arrow) { font:900 14px/1 Arial,sans-serif; text-shadow:0 1px 1px rgba(255,255,255,.22); transform:translateY(-.5px); }
.map-canvas :deep(.traffic-signal-countdown) { display:block; flex:1; min-width:0; margin-left:5px; border-left:1px solid rgba(255,255,255,.11); color:var(--lamp); font:800 19px/20px "DIN Alternate","Arial Narrow",Arial,sans-serif; font-variant-numeric:tabular-nums; letter-spacing:-.5px; text-align:center; text-shadow:0 0 6px color-mix(in srgb,var(--lamp),transparent 42%); }
.map-canvas :deep(.mission-point-marker) { --marker:#56ecff; display:flex; align-items:center; gap:6px; white-space:nowrap; pointer-events:none; filter:drop-shadow(0 2px 4px rgba(0,0,0,.7)); }
.map-canvas :deep(.mission-point-icon) { display:grid; width:28px; height:28px; place-items:center; border:1px solid color-mix(in srgb,var(--marker),white 20%); border-radius:50%; color:#03131c; background:var(--marker); box-shadow:0 0 16px color-mix(in srgb,var(--marker),transparent 35%); font:900 15px/1 sans-serif; }
.map-canvas :deep(.mission-point-label) { padding:4px 7px; border:1px solid color-mix(in srgb,var(--marker),transparent 45%); border-radius:3px; color:#e9fbff; background:rgba(3,18,29,.9); font:600 10px/1.2 sans-serif; }
.map-canvas :deep(.mission-point-marker.is-launch) { --marker:#44f0a8; }.map-canvas :deep(.mission-point-marker.is-recovery) { --marker:#ffd166; }.map-canvas :deep(.mission-point-marker.is-target) { --marker:#d277ff; }.map-canvas :deep(.mission-point-marker.is-target .mission-point-label) { margin-left:34px; }
.map-canvas :deep(.coin-reward-popup){padding:5px 9px;border:1px solid rgba(255,222,104,.72);border-radius:999px;color:#fff4a6;background:rgba(48,35,4,.9);box-shadow:0 0 20px rgba(255,200,45,.42);font:800 13px/1.1 "DIN Alternate",sans-serif;white-space:nowrap;pointer-events:none;animation:coin-reward-rise 1.1s ease-out forwards}@keyframes coin-reward-rise{0%{opacity:0;transform:translateY(12px) scale(.86)}18%{opacity:1;transform:translateY(0) scale(1.06)}72%{opacity:1;transform:translateY(-12px) scale(1)}100%{opacity:0;transform:translateY(-25px) scale(.94)}}
.map-canvas :deep(.coin-reward-popup.is-diamond){border-color:rgba(255,113,209,.82);color:#ffd2f3;background:rgba(57,4,43,.92);box-shadow:0 0 24px rgba(255,47,174,.58)}
.map-canvas :deep(.airspace-marker){--airspace:#ff496b;display:grid;gap:2px;min-width:84px;padding:5px 8px;border:1px solid color-mix(in srgb,var(--airspace),transparent 35%);border-radius:2px;color:#f8fbff;text-align:left;background:rgba(4,13,24,.82);box-shadow:0 0 15px color-mix(in srgb,var(--airspace),transparent 75%);backdrop-filter:blur(5px);cursor:pointer;pointer-events:auto;transform:translateY(-4px)}
.map-canvas :deep(.airspace-marker b){color:var(--airspace);font:700 10px/1.1 "Arial Narrow",sans-serif;letter-spacing:.09em}.map-canvas :deep(.airspace-marker span){color:#a9bac7;font:8px/1.1 sans-serif;letter-spacing:.05em}.map-canvas :deep(.airspace-marker.is-temporary_no_fly){--airspace:#ff8a47}.map-canvas :deep(.airspace-marker.is-risk_airspace){--airspace:#ffd166}.map-canvas :deep(.airspace-marker.is-altitude_restricted),.map-canvas :deep(.airspace-marker.is-altitude_corridor){--airspace:#9a72ff}.map-canvas :deep(.airspace-marker.threat-imminent),.map-canvas :deep(.airspace-marker.threat-violation){animation:airspace-alert .85s ease-in-out infinite alternate}
.map-canvas :deep(.airspace-marker.is-planning){border-width:2px;background:rgba(19,8,18,.94);box-shadow:0 0 22px color-mix(in srgb,var(--airspace),transparent 58%)}.map-canvas :deep(.airspace-marker.is-planning span){color:#f1dce3;font-weight:650}
.map-canvas :deep(.airspace-conflict-marker){display:grid;gap:2px;padding:6px 9px;border:1px solid #ff4768;border-radius:2px;color:#fff;text-align:left;background:rgba(47,5,17,.9);box-shadow:0 0 22px rgba(255,38,77,.36);cursor:pointer;pointer-events:auto}.map-canvas :deep(.airspace-conflict-marker b){color:#ff7890;font:800 9px/1 sans-serif;letter-spacing:.08em}.map-canvas :deep(.airspace-conflict-marker span){font:700 10px/1.1 "Arial Narrow",sans-serif}.map-canvas :deep(.airspace-conflict-marker.threat-imminent),.map-canvas :deep(.airspace-conflict-marker.threat-violation){animation:airspace-alert .7s ease-in-out infinite alternate}
@keyframes airspace-alert{to{box-shadow:0 0 28px color-mix(in srgb,var(--airspace),transparent 32%);transform:translateY(-4px) scale(1.05)}}
.map-status,.map-error { position:absolute; inset:0; z-index:30; display:grid; place-content:center; gap:8px; padding:30px; text-align:center; background:#071326; }
.map-status strong { font-size:18px; letter-spacing:2px; }.map-status small{color:#4d8fb2;letter-spacing:2px}.map-error{color:#ff8799}
.layer-switches { position:absolute; z-index:12; top:90px; left:230px; display:flex; gap:9px; padding:6px 9px; border:1px solid rgba(66,178,214,.24); border-radius:999px; background:rgba(5,20,38,.78); font-size:10px; backdrop-filter:blur(7px); }
.layer-switches label { cursor:pointer; white-space:nowrap; }.layer-switches input{margin-right:4px;accent-color:var(--signal-primary,#16d9ef)}
.traffic-status { position:absolute; z-index:12; top:126px; left:230px; padding:5px 9px; border:1px solid rgba(74,191,220,.35); border-radius:999px; color:#a8dcea; background:rgba(4,21,35,.82); font-size:10px; backdrop-filter:blur(7px); }
.traffic-status.is-ready { border-color:rgba(57,245,154,.5); color:#8fffc6; }.traffic-status.is-disabled,.traffic-status.is-degraded{border-color:rgba(255,83,104,.52);color:#ff8e9d}.traffic-status.is-empty{color:#9dafb9}
.planning-airspace-status{position:absolute;z-index:13;top:88px;left:50%;display:flex;align-items:center;gap:9px;min-width:176px;box-sizing:border-box;padding:7px 12px;border:1px solid rgba(255,72,101,.68);border-radius:3px;color:#ffe8ec;background:linear-gradient(90deg,rgba(63,8,25,.94),rgba(19,10,25,.9));box-shadow:0 0 26px rgba(255,50,86,.2);backdrop-filter:blur(8px);pointer-events:none;transform:translateX(-50%)}.planning-airspace-status>i{width:8px;height:8px;border:1px solid #ff9aaa;border-radius:50%;background:#ff3f64;box-shadow:0 0 12px #ff3f64;animation:planning-airspace-pulse 1.25s ease-in-out infinite}.planning-airspace-status>span{display:grid;gap:2px}.planning-airspace-status strong{font-size:10px;letter-spacing:.08em}.planning-airspace-status small{color:#c9aab3;font-size:8px}.planning-airspace-status.is-hidden{opacity:.58;filter:saturate(.35)}
.map-legend { position:absolute; z-index:13; right:18px; bottom:84px; display:grid; grid-template-columns:repeat(2,auto); gap:7px 14px; padding:10px 12px; border:1px solid rgba(66,178,214,.28); border-radius:6px; color:#789aaa; background:rgba(3,14,23,.92); box-shadow:0 14px 35px rgba(0,0,0,.3); font-size:9px; }
.map-legend i { display:inline-block; width:22px; margin-right:5px; border-top:2px dashed currentColor; vertical-align:middle; }.map-legend .ground-plan{color:#46dff2}.map-legend .ground-actual{color:#37f3cf;border-top-style:solid}.map-legend .air-plan{color:#a989ff}.map-legend .air-actual{color:#ef69ff;border-top-style:solid}.map-legend .no-fly{height:7px;border:1px solid #ff405f;background:rgba(255,64,95,.3)}.map-legend .reward-coin{width:9px;height:9px;border:1px solid #fff0a2;border-radius:50%;background:#e9a928;box-shadow:0 0 8px rgba(255,213,72,.65)}.map-legend .reward-diamond{width:9px;height:9px;border:1px solid #ffc3eb;background:#ff3eb5;box-shadow:0 0 11px rgba(255,55,183,.92);transform:rotate(45deg)}
.follow-controls { position:absolute; z-index:14; top:88px; left:50%; display:flex; align-items:center; gap:5px; padding:4px 5px 4px 10px; border:1px solid rgba(0,204,232,.38); border-radius:999px; background:rgba(4,23,43,.84); backdrop-filter:blur(8px); transform:translateX(-50%); }
.follow-controls span { max-width:145px; overflow:hidden; color:#8ec9dc; font-size:10px; text-overflow:ellipsis; white-space:nowrap; }.follow-controls button{padding:5px 9px;border:1px solid rgba(73,166,195,.34);border-radius:999px;color:#9cc7d8;background:rgba(10,48,70,.64);font-size:11px;cursor:pointer}.follow-controls button.active{border-color:var(--signal-primary,#35e3f4);color:#efffff;background:rgba(22,94,119,.82)}.follow-controls .overview-button{border-color:rgba(255,209,102,.5);color:#fff2c2;background:rgba(67,49,17,.68)}.follow-controls kbd{margin-left:3px;color:#94aeb9;font:9px monospace}
@media(max-width:1300px){.layer-switches,.traffic-status{left:215px}.follow-controls{left:46%;}}
@keyframes planning-airspace-pulse{50%{opacity:.35;transform:scale(.72)}}
@media(prefers-reduced-motion:reduce){.mission-map *{scroll-behavior:auto!important;animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important}}
</style>
