<template>
  <section class="model-viewer" aria-label="三维车型查看器">
    <div ref="viewport" class="viewer-viewport"></div>
    <div class="viewer-grid" aria-hidden="true"></div>
    <div v-if="loading" class="viewer-state" role="status"><b>正在载入模型</b><span>正在读取三维资产，请稍候…</span></div>
    <div v-else-if="error" class="viewer-state error" role="alert">
      <b>模型载入失败</b><span>{{ error }}</span><button type="button" @click="retry">重新加载</button>
    </div>
    <div class="viewer-meta">
      <span>单一三维查看器</span><span>拖拽旋转</span><span>滚轮缩放</span>
    </div>
    <button class="reset-view" type="button" @click="resetView">重置视角</button>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js'
import { clone as cloneSkeleton } from 'three/examples/jsm/utils/SkeletonUtils.js'
import { getModelAsset } from '../../config/modelAssets.mjs'
import { staticAssetCandidates } from '../../config/runtime'

const props = defineProps({ modelAssetId: { type: String, default: '' }, active: { type: Boolean, default: true } })
const viewport = ref(null)
const loading = ref(false)
const error = ref('')
const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder)
const cache = new Map()
const animationDisabled = new Set(['smart-city-drone'])
const reducedMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')
let renderer = null, scene = null, camera = null, controls = null, resizeObserver = null, raf = 0
let displayRoot = null, mixer = null, lastTime = 0, lastInteractionAt = 0, loadToken = 0

function setup() {
  scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2('#061019', .055)
  camera = new THREE.PerspectiveCamera(38, 1, .05, 100)
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(globalThis.devicePixelRatio || 1, 1.5))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.12
  renderer.domElement.dataset.fleetViewer = 'primary'
  viewport.value.appendChild(renderer.domElement)
  scene.add(new THREE.HemisphereLight('#c8fbff', '#071015', 2.4))
  const key = new THREE.DirectionalLight('#b8efff', 4.2); key.position.set(4, 7, 5); scene.add(key)
  const rim = new THREE.DirectionalLight('#65e9d2', 3.2); rim.position.set(-5, 3, -4); scene.add(rim)
  const platform = new THREE.Mesh(
    new THREE.CylinderGeometry(3.3, 3.6, .13, 64),
    new THREE.MeshStandardMaterial({ color: '#09202a', metalness: .75, roughness: .32, emissive: '#063b45', emissiveIntensity: .42 })
  )
  platform.position.y = -.1; scene.add(platform)
  const ring = new THREE.Mesh(new THREE.RingGeometry(2.45, 2.5, 64), new THREE.MeshBasicMaterial({ color: '#53e7ee', side: THREE.DoubleSide, transparent: true, opacity: .48 }))
  ring.rotation.x = -Math.PI / 2; ring.position.y = -.025; scene.add(ring)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enablePan = false; controls.enableDamping = true; controls.dampingFactor = .065
  controls.minDistance = 3.4; controls.maxDistance = 13; controls.maxPolarAngle = Math.PI * .54
  controls.autoRotateSpeed = .7
  for (const name of ['start', 'change']) controls.addEventListener(name, noteInteraction)
  resetView()
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(viewport.value)
  resize()
  startLoop()
}

function noteInteraction() { lastInteractionAt = performance.now() }
function resize() {
  if (!renderer || !viewport.value) return
  const width = Math.max(1, viewport.value.clientWidth), height = Math.max(1, viewport.value.clientHeight)
  renderer.setSize(width, height, false); camera.aspect = width / height; camera.updateProjectionMatrix()
}
function resetView() {
  if (!camera || !controls) return
  camera.position.set(6.4, 3.8, 7.4); controls.target.set(0, 1.25, 0); controls.update(); noteInteraction()
}
function startLoop() {
  if (raf || !props.active) return
  const frame = now => {
    if (!props.active) { raf = 0; return }
    const delta = Math.min(.05, lastTime ? (now - lastTime) / 1000 : 0); lastTime = now
    if (controls) controls.autoRotate = !reducedMotion?.matches && now - lastInteractionAt > 3500
    controls?.update(); mixer?.update(delta); renderer?.render(scene, camera)
    raf = requestAnimationFrame(frame)
  }
  raf = requestAnimationFrame(frame)
}
function stopLoop() { if (raf) cancelAnimationFrame(raf); raf = 0; lastTime = 0 }

function touchCache(id, entry) {
  cache.delete(id); cache.set(id, entry)
  while (cache.size > 3) {
    const [oldestId, oldest] = cache.entries().next().value
    cache.delete(oldestId); disposeTree(oldest.scene, true)
  }
}
async function cachedAsset(id) {
  if (cache.has(id)) { const entry = cache.get(id); touchCache(id, entry); return entry }
  const asset = getModelAsset(id)
  let gltf
  let lastError
  for (const candidate of staticAssetCandidates(asset.path)) {
    try {
      gltf = await loader.loadAsync(`${candidate}?v=fleet-hub-1`)
      break
    } catch (error) {
      lastError = error
    }
  }
  if (!gltf) throw lastError || new Error(`Unable to load ${asset.path}`)
  const entry = { scene: gltf.scene, animations: gltf.animations || [] }
  touchCache(id, entry)
  return entry
}
function clearDisplay() {
  mixer?.stopAllAction(); mixer = null
  if (!displayRoot) return
  scene?.remove(displayRoot)
  displayRoot.traverse(node => {
    if (!node.isMesh) return
    if (node.userData.viewerOwnedGeometry) node.geometry?.dispose?.()
    if (!node.material) return
    const materials = Array.isArray(node.material) ? node.material : [node.material]
    materials.filter(Boolean).forEach(material => material.dispose())
  })
  displayRoot = null
}
function freezeSkinnedPresentation(root) {
  root.updateMatrixWorld(true)
  const replacements = []
  root.traverse(node => {
    const sourcePosition = node.geometry?.attributes?.position
    if (!node.isSkinnedMesh || !sourcePosition) return
    const geometry = node.geometry.clone()
    const position = geometry.attributes.position
    const vertex = new THREE.Vector3()
    for (let index = 0; index < position.count; index += 1) {
      node.getVertexPosition(index, vertex)
      position.setXYZ(index, vertex.x, vertex.y, vertex.z)
    }
    position.needsUpdate = true
    geometry.deleteAttribute('skinIndex'); geometry.deleteAttribute('skinWeight')
    geometry.computeVertexNormals(); geometry.computeBoundingBox(); geometry.computeBoundingSphere()
    const mesh = new THREE.Mesh(geometry, node.material)
    mesh.name = node.name; mesh.position.copy(node.position); mesh.quaternion.copy(node.quaternion); mesh.scale.copy(node.scale)
    mesh.visible = node.visible; mesh.renderOrder = node.renderOrder; mesh.frustumCulled = false
    mesh.userData = { ...node.userData, viewerOwnedGeometry: true }
    replacements.push({ node, mesh })
  })
  replacements.forEach(({ node, mesh }) => { const parent = node.parent; parent?.remove(node); parent?.add(mesh) })
  root.updateMatrixWorld(true)
}
async function show(id) {
  const token = ++loadToken
  clearDisplay(); error.value = ''
  if (!id) return
  loading.value = true
  try {
    const entry = await cachedAsset(id)
    if (token !== loadToken || !scene) return
    const root = cloneSkeleton(entry.scene)
    root.traverse(node => {
      if (!node.isMesh || !node.material) return
      node.material = Array.isArray(node.material) ? node.material.map(material => material.clone()) : node.material.clone()
      node.castShadow = false; node.receiveShadow = false
    })
    if (animationDisabled.has(id)) freezeSkinnedPresentation(root)
    let box = new THREE.Box3().setFromObject(root), size = box.getSize(new THREE.Vector3())
    const scale = 4.8 / Math.max(size.x, size.y, size.z, .001)
    root.scale.setScalar(scale); root.updateMatrixWorld(true)
    box = new THREE.Box3().setFromObject(root); const center = box.getCenter(new THREE.Vector3())
    root.position.x -= center.x; root.position.z -= center.z; root.position.y -= box.min.y
    displayRoot = root; scene.add(root)
    // The drone's hover clip carries incompatible bone translations and
    // expands a one-metre mesh beyond 120 scene units. Keep its real bind pose
    // visible in the catalog while retaining interactive rotation and zoom.
    if (!animationDisabled.has(id) && entry.animations.length) {
      mixer = new THREE.AnimationMixer(root)
      mixer.clipAction(entry.animations[0]).play()
    }
    resetView()
  } catch (failure) {
    if (token === loadToken) error.value = failure?.message || '三维资产不可用'
  } finally {
    if (token === loadToken) loading.value = false
  }
}
function retry() { show(props.modelAssetId) }
function disposeTree(root, geometry) {
  root?.traverse(node => {
    if (!node.isMesh) return
    if (geometry) node.geometry?.dispose?.()
    const materials = Array.isArray(node.material) ? node.material : [node.material]
    materials.filter(Boolean).forEach(material => {
      if (geometry) Object.values(material).filter(value => value?.isTexture).forEach(texture => texture.dispose())
      material.dispose()
    })
  })
}

watch(() => props.modelAssetId, show)
watch(() => props.active, active => active ? startLoop() : stopLoop())
onMounted(() => { setup(); show(props.modelAssetId) })
onBeforeUnmount(() => {
  loadToken += 1; stopLoop(); resizeObserver?.disconnect(); clearDisplay()
  controls?.dispose(); cache.forEach(entry => disposeTree(entry.scene, true)); cache.clear()
  scene?.traverse(node => { if (node.isMesh && node !== displayRoot) { node.geometry?.dispose?.(); node.material?.dispose?.() } })
  renderer?.dispose(); renderer?.forceContextLoss(); renderer?.domElement.remove()
  renderer = scene = camera = controls = null
})
</script>

<style scoped>
.model-viewer { position:relative; min-height:0; overflow:hidden; border:1px solid rgba(124,231,238,.2); background:radial-gradient(circle at 50% 45%,rgba(18,70,78,.3),rgba(2,8,13,.92) 68%); }
.viewer-viewport { position:absolute; inset:0; z-index:2; }
.viewer-viewport :deep(canvas) { display:block; width:100%; height:100%; cursor:grab; }
.viewer-viewport :deep(canvas:active) { cursor:grabbing; }
.viewer-grid { position:absolute; inset:0; opacity:.14; background-image:linear-gradient(rgba(124,231,238,.18) 1px,transparent 1px),linear-gradient(90deg,rgba(124,231,238,.18) 1px,transparent 1px); background-size:40px 40px; mask-image:linear-gradient(to bottom,transparent,black 28%,black 82%,transparent); }
.viewer-state { position:absolute; inset:0; z-index:4; display:grid; place-content:center; gap:8px; text-align:center; color:var(--text-secondary); background:rgba(2,9,14,.72); }
.viewer-state b { color:var(--signal-primary); font:700 15px/1.4 sans-serif; letter-spacing:.08em; }
.viewer-state span { max-width:360px; font-size:14px; line-height:1.55; }
.viewer-state.error b { color:var(--signal-critical); }
.viewer-state button,.reset-view { min-height:36px; border:1px solid var(--surface-line-strong); color:var(--text-secondary); background:rgba(6,24,34,.9); font-size:14px; }
.viewer-state button { justify-self:center; padding:8px 15px; }
.viewer-meta { position:absolute; left:16px; bottom:13px; z-index:3; display:flex; gap:18px; color:var(--text-secondary); font:12px/1 monospace; letter-spacing:.04em; pointer-events:none; }
.reset-view { position:absolute; top:12px; right:12px; z-index:3; padding:7px 12px; }
.reset-view:hover,.reset-view:focus-visible { border-color:var(--signal-primary); color:var(--signal-primary); outline:none; }
@media (max-width:1350px), (max-height:820px) {
  .viewer-meta { left:12px; bottom:10px; gap:12px; font-size:12px; }
  .reset-view { top:9px; right:9px; }
}
</style>
