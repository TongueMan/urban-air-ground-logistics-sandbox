<template>
  <div class="mission-map" :class="{ 'is-replay': isReplay }">
    <div ref="mapEl" class="map-canvas"></div>
    <div v-if="mapLoading" class="map-status"><strong>三维协同场景启动中</strong><small>BAIDU MAPV THREE · PATROL MISSION</small></div>
    <div v-if="mapError" class="map-error">{{ mapError }}</div>

    <div class="mission-kpis">
      <div><span>任务进度</span><b>{{ number(mission.progress, 1) }}%</b></div>
      <div><span>协同覆盖</span><b>{{ number(coveragePercent, 1) }}%</b></div>
      <div><span>最大偏差</span><b>{{ number(maxDeviation, 1) }}m</b></div>
      <div><span>无人机均值电量</span><b>{{ number(averageBattery, 0) }}%</b></div>
      <div><span>链路健康</span><b :class="{ warn: averageLink < 70 }">{{ number(averageLink, 0) }}%</b></div>
    </div>

    <div class="layer-switches" aria-label="地图图层">
      <label><input v-model="layers.planned" type="checkbox">计划路线</label>
      <label><input v-model="layers.actual" type="checkbox">实际轨迹</label>
      <label><input v-model="layers.scan" type="checkbox">扫描范围</label>
    </div>
    <div class="map-legend">
      <span><i class="ground-plan"></i>地面计划</span><span><i class="ground-actual"></i>车辆实际</span>
      <span><i class="air-plan"></i>空中计划</span><span><i class="air-actual"></i>无人机实际</span>
    </div>
    <div v-if="followingId" class="follow-controls" aria-label="跟随视角" title="跟随中可使用鼠标滚轮缩放距离">
      <span>跟随 {{ followingDevice?.deviceName || followingId }}</span>
      <button type="button" :class="{ active: followMode === 'side' }" :aria-pressed="followMode === 'side'" @click="setFollowMode('side')">侧后方</button>
      <button type="button" :class="{ active: followMode === 'rear' }" :aria-pressed="followMode === 'rear'" @click="setFollowMode('rear')">正后方</button>
      <button class="overview-button" type="button" @click="leaveFollow">返回总览 <kbd>Esc</kbd></button>
    </div>

    <div v-if="selectedDevice" class="selected-card">
      <button class="close-card" type="button" aria-label="关闭设备详情" @click="$emit('select', '')">×</button>
      <strong>{{ selectedDevice.deviceName || selectedDevice.deviceId }}</strong>
      <small>{{ selectedDevice.deviceId }} · {{ selectedDevice.deviceType === 'smart_drone' ? '智慧无人机' : '巡检车' }}</small>
      <div class="selected-grid">
        <span>任务阶段</span><b>{{ phaseLabel(selectedMetrics.missionPhase) }}</b>
        <span>路线进度</span><b>{{ number(selectedMetrics.routeProgress, 1) }}%</b>
        <span>路线偏差</span><b>{{ number(selectedMetrics.routeDeviationMeters, 1) }}m</b>
        <template v-if="selectedDevice.deviceType === 'smart_drone'">
          <span>剩余电量</span><b>{{ number(selectedMetrics.battery, 0) }}%</b>
          <span>链路质量</span><b>{{ number(selectedMetrics.linkQuality, 0) }}%</b>
          <span>绑定车辆</span><b>{{ selectedMetrics.boundVehicleId || '—' }}</b>
        </template>
      </div>
      <button class="monitor-button" type="button" @click="$emit('open-monitor', selectedDevice)">打开监控</button>
    </div>

    <button v-if="evidenceEvent" class="evidence-card" type="button" @click="$emit('select', evidenceEvent.deviceId)">
      <span>仿真识别证据</span><strong>{{ evidenceEvent.label }}</strong>
      <small>{{ evidenceEvent.deviceId }} · 置信度 {{ number((evidenceEvent.confidence || 0) * 100, 0) }}%</small>
    </button>
    <div v-if="isReplay" class="replay-bar">
      <button type="button" @click="toggleReplay">{{ replaying ? '暂停' : '播放' }}</button>
      <input v-model.number="replayPercent" type="range" min="1" max="100"><span>{{ replayPercent }}%</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { clone as cloneSkeleton } from 'three/examples/jsm/utils/SkeletonUtils.js'
import * as mapvthree from '@baidumap/mapv-three'
import { runtimeConfig, staticAssetUrl } from '../config/runtime'
import { createBaiduCyberProvider } from '../config/baiduCyberMap'
import {
  adjustFollowZoomScale,
  bearingDegrees,
  createPolylineSampler,
  densifyCatmullRom,
  hasMissionSimulation,
  interpolateHeading,
  modelVisibilityScale,
  patrolFollowCamera,
  patrolModelPhaseState,
  selectMissionActualPoints
} from '../utils/patrolMotion.mjs'

const props = defineProps({
  devices: { type: Array, default: () => [] },
  mission: { type: Object, default: () => ({}) },
  selectedId: { type: String, default: '' },
  replayRate: { type: Number, default: 1 },
  simulationRate: { type: Number, default: 1 }
})
const emit = defineEmits(['select', 'open-monitor'])
const mapEl = ref(null), mapError = ref(''), mapLoading = ref(true), replayPercent = ref(100), replaying = ref(false), followingId = ref(''), followMode = ref('rear')
const layers = reactive({ planned: true, actual: true, scan: true })
let engine = null, resizeObserver = null, replayTimer = null, fitted = false, prepareRenderListener = null, modelTemplates = null, followUpdatedAt = 0, followZoomScale = 1, glowTexture = null
let hitLayer = null, pickLayer = null, scanLayer = null
const routeLayers = new Map(), routeSamplers = new Map(), modelRecords = new Map(), gltfLoader = new GLTFLoader()
const MODEL_ASSET_REVISION = 'patrol-real-model-v1'
const MODEL_URLS = {
  patrol_car: `${staticAssetUrl('models/smart-city/traffic/municipal-van.glb')}?v=${MODEL_ASSET_REVISION}`,
  smart_drone: `${staticAssetUrl('models/smart-city/devices/drone.glb')}?v=${MODEL_ASSET_REVISION}`
}
const MODEL_SIZE = { patrol_car: 5.25, smart_drone: 2.6 }
const MODEL_FORWARD_AXIS = { patrol_car: '+X', smart_drone: '+Y' }
const ROLE_COLORS = {
  patrol_car: new THREE.Color('#39e6ff'),
  smart_drone: new THREE.Color('#d46cff'),
  selected: new THREE.Color('#ffd166')
}
const OVERVIEW = { center: [117.285, 31.842, 0], heading: 12, pitch: 70, range: 12500 }
const MAP_UP = new THREE.Vector3(0, 0, 1)

const hasSimulation = computed(() => hasMissionSimulation(props.mission))
const isReplay = computed(() => hasSimulation.value && ['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(String(props.mission.state || '').toUpperCase()))
const selectedDevice = computed(() => props.devices.find(item => item.deviceId === props.selectedId))
const followingDevice = computed(() => props.devices.find(item => item.deviceId === followingId.value))
const latestMetrics = computed(() => {
  const result = new Map()
  ;(props.mission.routes || []).forEach(route => { const last = visibleActualPoints(route).at(-1); if (last?.metrics) result.set(route.deviceId, last.metrics) })
  if (hasSimulation.value) props.devices.forEach(device => { if (!result.has(device.deviceId)) result.set(device.deviceId, device.sensorData || {}) })
  return result
})
const selectedMetrics = computed(() => latestMetrics.value.get(props.selectedId)
  || (hasSimulation.value ? selectedDevice.value?.sensorData : {}) || {})
const droneMetrics = computed(() => props.devices
  .filter(item => item.deviceType === 'smart_drone')
  .map(item => latestMetrics.value.get(item.deviceId) || (hasSimulation.value ? item.sensorData : {}) || {}))
const averageBattery = computed(() => average(droneMetrics.value.map(item => Number(item.battery)).filter(Number.isFinite), null))
const averageLink = computed(() => average(droneMetrics.value.map(item => Number(item.linkQuality)).filter(Number.isFinite), null))
const maxDeviation = computed(() => {
  if (!hasSimulation.value) return null
  const values = (props.mission.routes || []).flatMap(route => (route.actualPoints || []).map(point => Number(point.metrics?.routeDeviationMeters)).filter(Number.isFinite))
  return values.length ? Math.max(...values) : 0
})
const coveragePercent = computed(() => average(droneMetrics.value.map(item => Number(item.coveragePercent)).filter(Number.isFinite), hasSimulation.value ? 0 : null))
const evidenceEvent = computed(() => (props.mission.events || []).find(event => event.type === 'ROAD_OBSTACLE' && event.reached))

function average(values, fallback) { return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : fallback }
function number(value, scale) { if (value === null || value === undefined || value === '') return '—'; const numeric = Number(value); return Number.isFinite(numeric) ? numeric.toFixed(scale) : '—' }
function phaseLabel(value) { return ({ DEPART: '编组出发', TAKEOFF: '定点放飞', SCANNING: '协同扫描', RETURNING: '返航会合', DOCKED: '车载待命' })[value] || value || '车载待命' }
function visibleActualPoints(route) {
  return selectMissionActualPoints(route, props.mission, replayPercent.value)
}
function coordinates(points, defaultHeight = 0) {
  return (points || []).map(point => Array.isArray(point)
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
function pointSource(points) {
  return mapvthree.GeoJSONDataSource.fromGeoJSON(featureCollection(points.map(item => ({ type: 'Feature', id: item.deviceId, geometry: { type: 'Point', coordinates: item.coordinate }, properties: { deviceId: item.deviceId } }))))
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
  const [width, height] = engine.map.getContainerSize()
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
  followUpdatedAt = 0
  emit('select', device.deviceId)
}

function tuneModelMaterial(type, node, source) {
  const material = source.clone()
  const key = `${node.name} ${material.name}`.toLowerCase()
  material.roughness = Math.min(Number(material.roughness ?? .65), .58)
  material.metalness = Math.max(Number(material.metalness ?? .15), .22)
  if (type === 'smart_drone') {
    material.color?.set('#eef3ff')
    if (material.emissive) material.emissive.set('#8e3bdb')
    material.emissiveIntensity = 1.2
    material.roughness = .34
    material.metalness = .38
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
  } else if (key.includes('taillight')) {
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

async function loadTemplate(type) {
  const gltf = await gltfLoader.loadAsync(MODEL_URLS[type])
  const root = gltf.scene
  root.traverse(node => {
    if (!node.isMesh || !node.material) return
    node.castShadow = false
    node.receiveShadow = false
    node.material = Array.isArray(node.material)
      ? node.material.map(material => tuneModelMaterial(type, node, material))
      : tuneModelMaterial(type, node, node.material)
  })
  const box = new THREE.Box3().setFromObject(root), size = box.getSize(new THREE.Vector3())
  return {
    scene: root,
    animations: gltf.animations || [],
    normalizedScale: MODEL_SIZE[type] / Math.max(size.x, size.y, size.z, .001)
  }
}
async function loadModelTemplates() {
  return Object.fromEntries(await Promise.all(Object.keys(MODEL_URLS).map(async type => {
    try { return [type, await loadTemplate(type)] } catch (error) {
      console.warn(`[PatrolMissionMap] ${type} 模型加载失败，已降级为几何标记`, error); return [type, null]
    }
  })))
}
function fallbackModel(type) {
  const material = new THREE.MeshStandardMaterial({ color: type === 'smart_drone' ? '#9d72ff' : '#00c8ff', emissive: '#00a8d4', emissiveIntensity: .6 })
  material.userData.baseOpacity = 1
  material.userData.baseTransparent = false
  material.userData.baseEmissiveIntensity = .6
  const mesh = type === 'smart_drone' ? new THREE.Mesh(new THREE.OctahedronGeometry(2.6, 0), material) : new THREE.Mesh(new THREE.BoxGeometry(5.25, 2.15, 2.25), material)
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

function createModelEffects(type) {
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
  return { halo, groundRing, altitudeLine }
}

function addVehicleBeacon(group, roofHeight) {
  const beacon = new THREE.Group()
  const cyan = new THREE.MeshBasicMaterial({ color: '#54f3ff', transparent: true, opacity: .95 })
  const amber = new THREE.MeshBasicMaterial({ color: '#ffbd4a', transparent: true, opacity: .48 })
  const geometry = new THREE.BoxGeometry(.34, .62, .17)
  const left = new THREE.Mesh(geometry, cyan), right = new THREE.Mesh(geometry, amber)
  left.position.set(0, -.33, roofHeight + .13); right.position.set(0, .33, roofHeight + .13)
  left.userData.instanceOwnedGeometry = true
  beacon.add(left, right); group.add(beacon)
  return { beacon, cyan, amber }
}

function cloneInstanceMaterials(object) {
  object.traverse(node => {
    if (!node.isMesh || !node.material) return
    node.material = Array.isArray(node.material) ? node.material.map(material => material.clone()) : node.material.clone()
  })
}

function createModelRecord(device, coordinate) {
  const type = device.deviceType === 'smart_drone' ? 'smart_drone' : 'patrol_car', group = new THREE.Group()
  const template = modelTemplates?.[type]
  const object = template ? (type === 'smart_drone' ? cloneSkeleton(template.scene) : template.scene.clone(true)) : fallbackModel(type)
  if (template) { cloneInstanceMaterials(object); object.scale.setScalar(template.normalizedScale) }
  object.rotation.x = Math.PI / 2
  object.updateMatrixWorld(true)
  const groundedBox = new THREE.Box3().setFromObject(object)
  object.position.z -= groundedBox.min.z
  group.add(object); engine.add(group)
  const effects = createModelEffects(type)
  const vehicleBeacon = type === 'patrol_car' ? addVehicleBeacon(group, groundedBox.max.z - groundedBox.min.z) : null
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
    group, object, type, current: coordinate.slice(), from: coordinate.slice(), to: coordinate.slice(),
    startedAt: performance.now(), duration: 900, currentProgress: 0, fromProgress: 0, toProgress: 0,
    sampler: null, heading: 0, currentHeading: 0, emphasized: true, selected: false, phase: 'DOCKED',
    phaseChangedAt: performance.now(),
    forwardAxis: MODEL_FORWARD_AXIS[type], displayScale: 1, lastFrameAt: performance.now(),
    mixer, animationAction, effects, vehicleBeacon
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
  if (!record) {
    record = createModelRecord(device, coordinate)
    record.currentProgress = Number(state.progress || 0)
    record.fromProgress = record.currentProgress
    record.toProgress = record.currentProgress
    record.sampler = state.sampler || null
    record.heading = Number(state.heading || 0)
    record.currentHeading = record.heading
    record.phase = String(state.phase || 'DOCKED').toUpperCase()
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
    record.startedAt = now; record.duration = isReplay.value ? 180 : 950
  }
  record.sampler = state.sampler || null
  record.heading = Number(state.heading ?? latestMetrics.value.get(device.deviceId)?.direction ?? 0)
  const nextPhase = String(state.phase || record.phase || 'DOCKED').toUpperCase()
  if (nextPhase !== record.phase) record.phaseChangedAt = now
  record.phase = nextPhase
  applyModelEmphasis(record, emphasized, device.deviceId === props.selectedId || device.deviceId === followingId.value)
}
function removeModelRecord(id) {
  const record = modelRecords.get(id)
  if (record) {
    record.mixer?.stopAllAction()
    engine?.remove(record.group); engine?.remove(record.effects.halo); engine?.remove(record.effects.groundRing); engine?.remove(record.effects.altitudeLine)
    record.object.traverse(node => {
      if (!node.isMesh) return
      const materials = Array.isArray(node.material) ? node.material : [node.material]
      materials.filter(Boolean).forEach(material => material.dispose())
      if (node.userData.instanceOwnedGeometry) node.geometry?.dispose()
    })
    record.effects.halo.material.dispose(); record.effects.groundRing.geometry.dispose(); record.effects.groundRing.material.dispose()
    record.effects.altitudeLine.geometry.dispose(); record.effects.altitudeLine.material.dispose()
  }
  modelRecords.delete(id)
}
function updateModels() {
  const now = performance.now()
  modelRecords.forEach((record, id) => {
    const deltaSeconds = Math.min(.05, Math.max(0, (now - record.lastFrameAt) / 1000)); record.lastFrameAt = now
    const ratio = Math.min(1, Math.max(0, (now - record.startedAt) / Math.max(1, record.duration))), eased = ratio * ratio * (3 - 2 * ratio)
    if (record.sampler) {
      record.currentProgress = record.fromProgress + (record.toProgress - record.fromProgress) * eased
      const sampled = record.sampler.locate(record.currentProgress)
      record.current = sampled.coordinate
      record.heading = sampled.heading
    } else {
      record.current = record.from.map((value, index) => value + (record.to[index] - value) * eased)
    }
    record.group.position.fromArray(engine.map.projectArrayCoordinate(record.current, []))
    record.currentHeading = interpolateHeading(record.currentHeading, record.heading, Math.min(.18, .06 + ratio * .12))
    const baseYaw = record.type === 'patrol_car' && record.forwardAxis === '+Z' ? Math.PI : record.type === 'patrol_car' ? Math.PI / 2 : 0
    const yaw = baseYaw - THREE.MathUtils.degToRad(record.currentHeading)
    const targetQuaternion = new THREE.Quaternion().setFromAxisAngle(MAP_UP, yaw)
    record.group.quaternion.slerp(targetQuaternion, .18)
    const phaseState = patrolModelPhaseState(record.type, record.phase)
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
      takeoffProgress
    })
    record.displayScale = THREE.MathUtils.lerp(record.displayScale, targetScale, .14)
    record.group.scale.setScalar(record.displayScale)
    const worldUnitsPerPixel = 2 * cameraDistance * Math.tan(THREE.MathUtils.degToRad(engine.camera.fov) / 2) / Math.max(1, mapEl.value?.clientHeight || 1)
    const roleOpacity = record.emphasized ? 1 : .3
    const haloPixels = record.selected ? 36 : record.type === 'smart_drone' ? 30 : 28
    record.effects.halo.position.copy(record.group.position)
    record.effects.halo.position.z += MODEL_SIZE[record.type] * record.displayScale * .52
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
    if (followingId.value === id && now - followUpdatedAt > 90) {
      followUpdatedAt = now
      const camera = patrolFollowCamera(record.type, record.currentHeading, followMode.value)
      engine.map.lookAt(record.current, { ...camera, range: camera.range * followZoomScale })
    }
  })
}

function createRouteVisual(route) {
  const air = route.kind === 'AIR'
  // MapV Three 1.6.x 的 Polyline 外层只在构造阶段接收基础材质参数。
  // 高度与动画参数在对象建立后通过代理属性设置，避免其被错误传给
  // THREE.ShaderMaterial 并在每次进入页面时产生 unknown-property 警告。
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
  Object.assign(actual, {
    height: air ? .15 : .22,
    enableAnimation: true,
    enableAnimationChaos: true,
    animationSpeed: air ? .65 : 1,
    animationTailType: 1,
    animationTailRatio: air ? .28 : .2,
    animationIdle: 260
  })
  const visual = { planned, actual }; routeLayers.set(route.deviceId, visual); return visual
}
function updateRoutes() {
  // 设备轮询可能早于 GLB 下载完成。模型模板就绪前不创建记录，避免
  // 将待命车辆永久固化成 fallbackModel() 的盒子/八面体。
  if (!engine || !hitLayer || !modelTemplates) return
  const routeIds = new Set(), latest = new Map(), viewportPoints = [], highlighted = pairDeviceIds()
  ;(props.mission.routes || []).forEach(route => {
    routeIds.add(route.deviceId)
    const visual = routeLayers.get(route.deviceId) || createRouteVisual(route)
    const rawPlanned = routeCoordinates(route)
    const planned = route.kind === 'AIR'
      ? densifyCatmullRom(rawPlanned, 16).map(point => [point[0], point[1], Math.max(3, point[2])])
      : rawPlanned
    let sampler = routeSamplers.get(route.deviceId)
    const samplerKey = `${planned.length}:${planned[0]?.join(',')}:${planned.at(-1)?.join(',')}`
    if (!sampler || sampler.key !== samplerKey) { sampler = Object.assign(createPolylineSampler(planned), { key: samplerKey }); routeSamplers.set(route.deviceId, sampler) }
    const metrics = latestMetrics.value.get(route.deviceId) || {}
    const actualRaw = routeCoordinates(route, visibleActualPoints(route))
    // 百度 JSAPI Three 的 Polyline 直接消费 GeoJSON LineString。实际轨迹保留
    // 遥测点原序列，避免展示性曲线拟合越过真实位置或道路中心线。
    const actual = actualRaw
    visual.planned.dataSource = lineSource(planned, `${route.deviceId}-planned`); visual.actual.dataSource = lineSource(actual, `${route.deviceId}-actual`)
    visual.planned.visible = layers.planned && planned.length > 1; visual.actual.visible = hasSimulation.value && layers.actual && actual.length > 1
    const emphasized = !highlighted.size || highlighted.has(route.deviceId); visual.planned.opacity = emphasized ? .5 : .16; visual.actual.opacity = emphasized ? .98 : .28
    const coordinate = actual.length ? actual.at(-1) : planned[0]
    const heading = actual.length > 1
      ? bearingDegrees(actual.at(-2), actual.at(-1))
      : Number(metrics.direction ?? sampler.locate(0).heading ?? 0)
    const phase = metrics.missionPhase || (!hasSimulation.value ? 'DOCKED' : props.mission.missionPhase)
    latest.set(route.deviceId, { coordinate, heading, progress: Number(metrics.routeProgress || 0) / 100, sampler: null, phase })
    viewportPoints.push(...planned)
  })
  routeLayers.forEach((visual, id) => { if (!routeIds.has(id)) { engine.remove(visual.planned); engine.remove(visual.actual); routeLayers.delete(id) } })
  const deviceIds = new Set(), hitPoints = [], scanPoints = []
  ;(props.mission.pairs || []).forEach(pair => {
    const vehicle = latest.get(pair.vehicleId), drone = latest.get(pair.droneId)
    if (!vehicle || !drone || !['DEPART', 'DOCKED'].includes(String(drone.phase || '').toUpperCase())) return
    drone.coordinate = [vehicle.coordinate[0], vehicle.coordinate[1], vehicle.coordinate[2] + 2.55]
    drone.heading = vehicle.heading
  })
  props.devices.forEach(device => {
    const state = latest.get(device.deviceId) || { coordinate: [Number(device.longitude), Number(device.latitude), Number(device.altitude || (device.deviceType === 'smart_drone' ? 70 : .3))], heading: Number(device.sensorData?.direction || 0), progress: 0, sampler: null }
    if (!state.coordinate.every(Number.isFinite)) return
    deviceIds.add(device.deviceId); updateModelTarget(device, state, !highlighted.size || highlighted.has(device.deviceId)); hitPoints.push({ deviceId: device.deviceId, coordinate: state.coordinate })
    if (hasSimulation.value && device.deviceType === 'smart_drone' && latestMetrics.value.get(device.deviceId)?.scanActive === true) scanPoints.push({ deviceId: device.deviceId, coordinate: state.coordinate })
  })
  modelRecords.forEach((_, id) => { if (!deviceIds.has(id)) removeModelRecord(id) })
  hitLayer.dataSource = pointSource(hitPoints); pickLayer.dataSource = pointSource(hitPoints); scanLayer.dataSource = pointSource(scanPoints); scanLayer.visible = layers.scan && scanPoints.length > 0
  if (!fitted && viewportPoints.length > 1) { engine.map.setViewport(viewportPoints, { range: OVERVIEW.range }); engine.map.setHeading(OVERVIEW.heading); engine.map.setPitch(OVERVIEW.pitch); fitted = true }
  engine.requestRender()
}

async function initMap() {
  const ak = runtimeConfig.baiduMapAk.trim(); if (!ak) throw new Error('未配置百度地图浏览器端 AK'); if (!mapEl.value) return
  window.MAPV_BASE_URL = '/mapvthree/'
  const provider = createBaiduCyberProvider(mapvthree, THREE, ak)
  engine = new mapvthree.Engine(mapEl.value, { map: { projection: 'EPSG:3857', center: OVERVIEW.center, heading: OVERVIEW.heading, pitch: OVERVIEW.pitch, range: OVERVIEW.range, provider: null }, rendering: { sky: null, enableAnimationLoop: true, animationLoopFrameTime: 24, pixelRatio: Math.min(window.devicePixelRatio || 1, 1.6), features: { bloom: { enabled: true, strength: .42, threshold: .61, radius: .42 } } }, widgets: { enabled: false } })
  engine.map.setMinRange(45); engine.map.setMaxRange(18000)
  if (engine.map.control) { engine.map.control.zoomSpeed = .0016; engine.map.control.inertiaZoom = .82 }
  const sky = engine.add(new mapvthree.DefaultSky()); sky.color = new THREE.Color('#0a1c33'); sky.highColor = new THREE.Color('#020817')
  engine.add(new mapvthree.MapView({ terrainProvider: null, vectorProvider: provider }))
  hitLayer = engine.add(new mapvthree.EffectPoint({ type: 'RadarLayered', color: '#00e9ff', sideColor: '#34f5c5', size: 30, height: 1, duration: 1600, keepSize: true, opacity: .18 }))
  pickLayer = engine.add(new mapvthree.EffectModelPoint({ normalize: true, rotateToZUp: false, keepSize: true, size: 38, height: 1, animationRotate: false }))
  pickLayer.model = new THREE.Mesh(
    new THREE.BoxGeometry(1.8, 1.8, 1.8),
    new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false, colorWrite: false })
  )
  scanLayer = engine.add(new mapvthree.EffectPoint({ type: 'RadarLayered', color: '#ae69ff', sideColor: '#d67cff', size: Number(props.mission.scanRadiusMeters || 250) / 3, height: 0, duration: 2200, keepSize: false, opacity: .14 }))
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
  modelTemplates = await loadModelTemplates(); prepareRenderListener = updateModels; engine.addPrepareRenderListener(prepareRenderListener)
  updateRoutes(); mapLoading.value = false
}
function setFollowMode(mode) { followMode.value = mode === 'side' ? 'side' : 'rear'; followUpdatedAt = 0 }
function leaveFollow() { followingId.value = ''; followZoomScale = 1; engine?.map.flyTo(OVERVIEW.center, { heading: OVERVIEW.heading, pitch: OVERVIEW.pitch, range: OVERVIEW.range, duration: 850 }) }
function onFollowWheel(event) {
  if (!followingId.value) return
  event.preventDefault()
  event.stopPropagation()
  followZoomScale = adjustFollowZoomScale(followZoomScale, event.deltaY)
  followUpdatedAt = 0
  engine?.requestRender()
}
function onKeydown(event) { if (event.key === 'Escape' && followingId.value) leaveFollow() }
function toggleReplay() {
  replaying.value = !replaying.value
  if (!replaying.value) { window.clearInterval(replayTimer); replayTimer = null; return }
  if (replayPercent.value >= 100) replayPercent.value = 1
  const interval = Math.max(24, 120 / Math.max(.25, Number(props.replayRate) || 1))
  replayTimer = window.setInterval(() => { replayPercent.value = Math.min(100, replayPercent.value + 1); if (replayPercent.value >= 100) toggleReplay() }, interval)
}
watch([() => props.devices, () => props.mission, () => props.selectedId, layers, replayPercent], updateRoutes, { deep: true })
watch(() => props.selectedId, (deviceId) => {
  if (!deviceId) return
  if (props.devices.some(item => item.deviceId === deviceId)) {
    if (followingId.value !== deviceId) followZoomScale = 1
    followingId.value = deviceId
  }
})
watch(isReplay, value => { if (!value) replayPercent.value = 100 })
onMounted(() => { window.addEventListener('keydown', onKeydown); mapEl.value?.addEventListener('wheel', onFollowWheel, { capture: true, passive: false }); initMap().catch(error => { console.error('[PatrolMissionMap] 百度 MapV Three 初始化失败：', error); mapLoading.value = false; mapError.value = `${error?.message || '三维地图初始化失败'}。请检查百度浏览器端 AK、Referer 白名单及网络连接。` }) })
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  mapEl.value?.removeEventListener('wheel', onFollowWheel, true)
  if (replayTimer) window.clearInterval(replayTimer)
  resizeObserver?.disconnect()
  if (prepareRenderListener) engine?.removePrepareRenderListener(prepareRenderListener)
  Array.from(modelRecords.keys()).forEach(removeModelRecord)
  routeLayers.clear(); routeSamplers.clear()
  glowTexture?.dispose(); glowTexture = null
  engine?.dispose(); engine = null
})
</script>

<style scoped>
.mission-map{--mission-left-safe:322px;--mission-right-safe:342px;position:relative;width:100%;height:100%;min-height:520px;overflow:hidden;color:#dff7ff;background:#071326;isolation:isolate}.map-canvas{position:absolute;inset:0;z-index:0}.map-canvas :deep(canvas){display:block;width:100%;height:100%}.map-status,.map-error{position:absolute;inset:0;z-index:30;display:grid;place-content:center;gap:8px;text-align:center;background:#071326}.map-status strong{font-size:18px;letter-spacing:2px}.map-status small{color:#4d8fb2;letter-spacing:2px}.map-error{color:#ff8799;padding:30px}
.mission-kpis{position:absolute;z-index:12;top:9px;left:50%;transform:translateX(-50%);display:grid;grid-template-columns:repeat(5,minmax(96px,1fr));width:min(610px,calc(100% - var(--mission-left-safe) - var(--mission-right-safe) - 44px));min-width:500px;border:1px solid rgba(57,203,236,.34);border-radius:999px;background:rgba(4,20,39,.82);box-shadow:0 8px 20px rgba(0,0,0,.24);backdrop-filter:blur(8px)}.mission-kpis div{display:flex;align-items:baseline;justify-content:center;gap:5px;padding:6px 9px;text-align:center;border-right:1px solid rgba(73,152,187,.19)}.mission-kpis div:last-child{border:0}.mission-kpis span{color:#759bb4;font-size:9px;white-space:nowrap}.mission-kpis b{font-size:16px;line-height:1}.mission-kpis b.warn{color:#ffad63}
.layer-switches{position:absolute;z-index:12;top:51px;right:calc(var(--mission-right-safe) + 14px);display:flex;gap:9px;padding:6px 9px;border:1px solid rgba(66,178,214,.24);border-radius:999px;background:rgba(5,20,38,.78);font-size:10px;backdrop-filter:blur(7px)}.layer-switches label{cursor:pointer;white-space:nowrap}.layer-switches input{accent-color:#16d9ef;margin-right:4px}.map-legend{position:absolute;z-index:12;left:calc(var(--mission-left-safe) + 18px);bottom:18px;display:grid;grid-template-columns:repeat(2,auto);gap:8px 18px;padding:10px 14px;border:1px solid rgba(66,178,214,.28);border-radius:9px;background:rgba(5,20,38,.88);color:#8eb2c8;font-size:11px}.map-legend i{display:inline-block;width:28px;margin-right:7px;border-top:2px dashed currentColor;vertical-align:middle}.map-legend .ground-plan{color:#46dff2}.map-legend .ground-actual{color:#37f3cf;border-top-style:solid}.map-legend .air-plan{color:#a989ff}.map-legend .air-actual{color:#ef69ff;border-top-style:solid}
.follow-controls{position:absolute;z-index:14;top:49px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:5px;padding:4px 5px 4px 10px;border:1px solid rgba(0,204,232,.48);border-radius:999px;background:rgba(4,23,43,.84);box-shadow:0 8px 20px rgba(0,0,0,.24);backdrop-filter:blur(8px)}.follow-controls span{max-width:145px;overflow:hidden;color:#8ec9dc;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.follow-controls button{border:1px solid rgba(73,166,195,.34);border-radius:999px;padding:5px 9px;color:#9cc7d8;background:rgba(10,48,70,.64);font-size:11px;cursor:pointer}.follow-controls button.active{border-color:#35e3f4;color:#efffff;background:linear-gradient(90deg,rgba(0,145,184,.82),rgba(46,99,185,.82));box-shadow:0 0 10px rgba(32,218,240,.24)}.follow-controls .overview-button{border-color:rgba(255,209,102,.5);color:#fff2c2;background:rgba(67,49,17,.68)}.follow-controls kbd{margin-left:3px;color:#94aeb9;font:9px monospace}.selected-card{position:absolute;z-index:14;left:calc(var(--mission-left-safe) + 18px);top:94px;width:244px;padding:13px;border:1px solid rgba(0,222,244,.48);border-radius:10px;background:rgba(4,20,39,.88);box-shadow:0 12px 28px rgba(0,0,0,.3);backdrop-filter:blur(8px)}.selected-card>strong,.selected-card>small{display:block}.selected-card>strong{padding-right:22px;font-size:15px}.selected-card>small{margin-top:3px;color:#6597b4;font-size:11px}.close-card{position:absolute;right:7px;top:5px;border:0;color:#7aa9c2;background:transparent;font-size:18px;cursor:pointer}.selected-grid{display:grid;grid-template-columns:1fr auto;gap:5px;margin-top:10px;padding-top:9px;border-top:1px solid rgba(74,142,177,.18);font-size:11px}.selected-grid span{color:#7193aa}.selected-grid b{text-align:right}.monitor-button{width:100%;margin-top:10px;padding:6px;border:1px solid #00cfe9;border-radius:6px;color:#dffaff;background:linear-gradient(90deg,#087da9,#1358a5);cursor:pointer}
.evidence-card{position:absolute;z-index:13;right:calc(var(--mission-right-safe) + 18px);bottom:18px;width:230px;padding:12px;border:1px solid rgba(255,91,117,.62);border-radius:9px;color:#dff7ff;background:rgba(39,10,26,.9);text-align:left;cursor:pointer}.evidence-card span,.evidence-card strong,.evidence-card small{display:block}.evidence-card span{color:#ff7890;font-size:10px}.evidence-card strong{margin:5px 0}.evidence-card small{color:#bb91a3}.replay-bar{position:absolute;z-index:15;left:50%;bottom:18px;transform:translateX(-50%);display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid rgba(76,182,223,.4);border-radius:999px;background:rgba(4,20,39,.92)}.replay-bar button{border:0;color:#dffaff;background:transparent;cursor:pointer}.replay-bar input{width:210px;accent-color:#16d9ef}
@media(max-width:1500px){.mission-kpis{width:500px}.mission-kpis div{flex-direction:column;align-items:center;gap:1px;padding:4px 6px}.mission-kpis span{font-size:8px}.mission-kpis b{font-size:14px}.selected-card{width:218px}.layer-switches,.evidence-card{right:16px}.map-legend{left:16px}}@media(prefers-reduced-motion:reduce){.mission-map *{scroll-behavior:auto!important;animation-duration:.01ms!important;animation-iteration-count:1!important;transition-duration:.01ms!important}}
</style>
