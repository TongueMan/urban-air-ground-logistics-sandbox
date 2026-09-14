/**
 * Canonical registry for every GLB shipped with the frontend.
 *
 * `path` is relative to `frontend/public`, so it can be passed directly to
 * staticAssetUrl(). Fleet assets are loaded on demand after the operator
 * selects or deploys them.
 */
export const MODEL_ASSETS = Object.freeze({
  'trophy-low-poly-game-ready': Object.freeze({
    id: 'trophy-low-poly-game-ready',
    displayName: 'Trophy - Low Poly - Game Ready',
    category: 'reward',
    lifecycle: 'active',
    path: 'models/library/rewards/trophy-low-poly-game-ready.glb',
    bytes: 42708,
    sha256: '345fc6de68a1f0dd0b4089c013430135d37b5e2dea8af381bfce0e23495cfe5a',
    scene: Object.freeze({ nodes: 6, meshes: 2, materials: 2, textures: 0, animations: 0, skins: 0 }),
    license: 'CC-BY-4.0',
    author: 'Bob.Ho',
    source: 'https://sketchfab.com/3d-models/trophy-low-poly-game-ready-23ff15bc69d44c21be3fb5a4c3731527',
    notes: '进阶地面路线奖杯；源模型 Y-up，地图展示时校正为 Z-up 并绕自身竖直轴旋转。'
  }),
  'pink-diamond': Object.freeze({
    id: 'pink-diamond',
    displayName: 'Pink Diamond Airspace Reward',
    category: 'reward',
    lifecycle: 'active',
    path: 'models/library/rewards/pink-diamond.glb',
    bytes: 13472,
    sha256: 'dc1bfdf76112c9951eb2fdc7c0b86dc03a4b2d91c794348f2bce116221ed06ce',
    scene: Object.freeze({ nodes: 10, meshes: 1, materials: 1, textures: 0, animations: 0, skins: 0 }),
    license: 'CC-BY-4.0',
    author: 'gabrisciarrone',
    source: 'https://sketchfab.com/3d-models/pink-diamond-fcdd2b8c52f0473f93b8f0c1acc6a2f1',
    notes: '禁飞区处置挑战奖励；仅在绑定的绕飞或爬升航线上可拾取。'
  }),
  'gold-coin': Object.freeze({
    id: 'gold-coin',
    displayName: 'Delivery Reward Coin',
    category: 'reward',
    lifecycle: 'active',
    path: 'models/library/rewards/gold-coin.glb',
    bytes: 57128,
    sha256: 'f870f7744f8f393498cf937c9e5c6b37338bad2a74236f0355b293229b21a31f',
    scene: Object.freeze({ nodes: 6, meshes: 2, materials: 2, textures: 0, animations: 0, skins: 0 }),
    license: 'CC-BY-4.0',
    author: 'TomaszObloj',
    source: 'https://sketchfab.com/3d-models/gold-coin-ede8f6b0f13f45fb9b9a2efc9a09bae8',
    notes: '配送收益视觉反馈；原始模型以 Z 为竖直轴，外层 Pivot 负责地图自转。'
  }),
  'ford-f350-utility': Object.freeze({
    id: 'ford-f350-utility',
    displayName: 'Ford F-350 Utility',
    category: 'ground-vehicle',
    lifecycle: 'active',
    path: 'models/smart-city/traffic/ford-f350-utility.glb',
    bytes: 989556,
    sha256: '23b3d51855eff694d0ccb966725f07b4359df4be6c155be596101853f8387760',
    scene: Object.freeze({ nodes: 11, meshes: 9, materials: 8, textures: 5, animations: 0, skins: 0 }),
    license: 'CC-BY-4.0',
    author: 'Doz3',
    source: 'https://sketchfab.com/3d-models/ford-f350-ultity-e9102d754ed44830a44f78dded447124',
    notes: '多用途物流皮卡；保留警示灯和车载无人机停机位标定。'
  }),
  'highway-patrol-cruiser': Object.freeze({
    id: 'highway-patrol-cruiser',
    displayName: 'Highway Patrol Cruiser',
    category: 'operations',
    lifecycle: 'active',
    path: 'models/library/operations/highway-patrol-cruiser.glb',
    bytes: 5409552,
    sha256: '2d55b8f41200fc94a49d5973306ac65162012a0e356815040c00b85d8b00bc87',
    scene: Object.freeze({ nodes: 48, meshes: 26, materials: 19, textures: 9, animations: 1, skins: 0 }),
    extensions: Object.freeze(['KHR_materials_emissive_strength', 'KHR_materials_specular', 'KHR_materials_transmission']),
    license: 'CC-BY-4.0',
    author: 'Mateusz Woliński (jeandiz)',
    source: 'https://sketchfab.com/3d-models/highway-patrol-cruiser-3d85e608d2c847c58577074faa134cbc',
    notes: '合同监管车辆专用模型；保留原始材质，红蓝警灯由运行态以双闪节奏交替驱动。'
  }),
  'smart-city-drone': Object.freeze({
    id: 'smart-city-drone',
    displayName: 'Smart City Logistics Drone',
    category: 'uav',
    lifecycle: 'active',
    path: 'models/smart-city/devices/drone.glb',
    bytes: 3417916,
    sha256: '3647feb65c73875e9ba5b2a33374f506e3ed3eac1d1050f7f3156065239f7b6d',
    scene: Object.freeze({ nodes: 249, meshes: 1, materials: 1, textures: 0, animations: 1, skins: 1 }),
    license: 'NON-COMMERCIAL',
    source: null,
    notes: '当前轻型配送无人机模型；带骨骼和 1 段动画。项目所有者确认该模型可免费用于并随本非商业项目发布。'
  }),
  'peterbilt-379-optimus-prime': Object.freeze({
    id: 'peterbilt-379-optimus-prime',
    displayName: 'Peterbilt 379 (Optimus Prime)',
    category: 'ground-vehicle',
    lifecycle: 'active',
    path: 'models/library/ground-vehicles/peterbilt-379-optimus-prime.glb',
    bytes: 3602468,
    sha256: 'c58ed40820e11e3f8a4c30d985e230c4358de2c3d2cb1168ef2b173048d98b32',
    scene: Object.freeze({ nodes: 23, meshes: 13, materials: 7, textures: 21, animations: 0, skins: 0 }),
    extensions: Object.freeze(['EXT_meshopt_compression', 'EXT_texture_webp']),
    optimization: Object.freeze({ originalBytes: 53805044, originalPolygons: 757638, optimizedPolygons: 186949, textureMaxSize: 512 }),
    license: 'CC-BY-4.0',
    author: 'LinkinPipe',
    source: 'https://sketchfab.com/3d-models/peterbilt-379-optimus-prime-12faed17298947458cccee85b45a9695',
    notes: 'Web 优化完成；由 53.8 MB 降至 3.60 MB，保留 18.7 万面和 512 px WebP 纹理。'
  }),
  'cybertruck-fun-size': Object.freeze({
    id: 'cybertruck-fun-size',
    displayName: 'Cybertruck - Fun Size',
    category: 'ground-vehicle',
    lifecycle: 'active',
    path: 'models/library/ground-vehicles/cybertruck-fun-size.glb',
    bytes: 2990328,
    sha256: '95d528a7366b86a57d820c261efb51b6f4af006fc7ca6e1fdf0ad25b8e64b408',
    scene: Object.freeze({ nodes: 50, meshes: 20, materials: 8, textures: 2, animations: 0, skins: 0 }),
    extensions: Object.freeze(['EXT_meshopt_compression', 'KHR_materials_clearcoat']),
    optimization: Object.freeze({ originalBytes: 6220676, originalObjects: 24, vehicleObjects: 20, originalPolygons: 121429, vehiclePolygons: 120662, removedAnimation: 'Take 001' }),
    license: 'CC-BY-NC-4.0',
    author: 'carlosortega3d',
    source: 'https://sketchfab.com/3d-models/cybertruck-fun-size-6d5e91d7acee4b42af045531245774ca',
    notes: '车辆提取完成；已移除碰撞演示的两块石头、碎玻璃、辅助灯位和动画，重新居中贴地并启用 Meshopt 压缩。'
  }),
  tricycle: Object.freeze({
    id: 'tricycle',
    displayName: 'Tricycle',
    category: 'ground-vehicle',
    lifecycle: 'active',
    path: 'models/library/ground-vehicles/tricycle.glb',
    bytes: 1227656,
    sha256: 'a08a02944915ddbbe9a5b9032bcd666aacd48eaf2ce47b5728faf6ad67928c15',
    scene: Object.freeze({ nodes: 6, meshes: 4, materials: 3, textures: 1, animations: 0, skins: 0 }),
    license: 'CC-BY-4.0',
    author: 'zunkas',
    source: 'https://sketchfab.com/3d-models/tricycle-obj-2c3da4094a8341a4956a4bc43dd7054d',
    notes: '当前地面运输任务模型；按约 3.2 m 车长与 +X 前向轴标定，不展示皮卡警示灯或 Drone Bay。'
  }),
  'ural-truck-vehicle-only': Object.freeze({
    id: 'ural-truck-vehicle-only',
    displayName: 'Ural Truck (Vehicle Only)',
    category: 'ground-vehicle',
    lifecycle: 'active',
    path: 'models/library/ground-vehicles/ural-truck-vehicle-only.glb',
    bytes: 741728,
    sha256: 'c5d87c935e7f8ac972a9f2c8504ffac42a001c8b7089501e52e5f34a10c5dc6e',
    scene: Object.freeze({ nodes: 21, meshes: 20, materials: 3, textures: 3, animations: 0, skins: 0 }),
    extensions: Object.freeze(['EXT_meshopt_compression', 'KHR_materials_unlit']),
    optimization: Object.freeze({ originalBytes: 4951436, originalObjects: 35, vehicleObjects: 20, originalPolygons: 65578, vehiclePolygons: 30326 }),
    license: 'CC-BY-4.0',
    author: 'thcyrax',
    source: 'https://sketchfab.com/3d-models/ural-truck-diorama-low-poly-0f5af026e75248aaa7010990edbf677b',
    notes: '车辆拆分完成；已移除地台、草木、岩石和树木，仅保留六轮卡车并重新居中贴地。'
  }),
  'vtol-air-taxi': Object.freeze({
    id: 'vtol-air-taxi',
    displayName: 'VTOL Air Taxi',
    category: 'aircraft',
    lifecycle: 'active',
    path: 'models/library/aircraft/vtol-air-taxi.glb',
    bytes: 24881072,
    sha256: 'adfed78c29341ce0caac29bfbd9597175c6007622d341c4d51dd949421314fed',
    scene: Object.freeze({ nodes: 175, meshes: 65, materials: 4, textures: 11, animations: 1, skins: 1 }),
    license: 'CC-BY-4.0',
    author: 'Annelida',
    source: 'https://sketchfab.com/3d-models/vtol-air-taxi-ecb6d12a90514aab9105061a34c7d604',
    notes: 'VTOL 重载运输飞行器；约 24.9 MB，带骨骼动画并采用按需加载。'
  })
})

/**
 * Map-scene presentation calibrated from axis-stable Blender top renders.
 * `forwardAxis` is the authored nose/front direction after the shared Y-up to
 * Z-up scene rotation and before the geometry-derived route heading is applied.
 */
export const MODEL_MAP_PRESENTATION = Object.freeze({
  tricycle: Object.freeze({
    size: 3.2,
    forwardAxis: '+X',
    warningBeacon: false,
    visibleDroneDock: false,
    compatibilityDock: Object.freeze({ x: -.55, y: 0, z: 1.22, radius: .48 })
  }),
  'ford-f350-utility': Object.freeze({
    size: 6.7,
    forwardAxis: '+Y',
    warningBeacon: true,
    visibleDroneDock: true,
    compatibilityDock: Object.freeze({ x: 0, y: -1.85, z: 1.86, radius: .72 })
  }),
  'highway-patrol-cruiser': Object.freeze({
    size: 5.4,
    forwardAxis: '-Y',
    warningBeacon: false,
    visibleDroneDock: false,
    emergencyLightbar: true
  }),
  'ural-truck-vehicle-only': Object.freeze({ size: 7.8, forwardAxis: '-Y', warningBeacon: false, visibleDroneDock: false }),
  'cybertruck-fun-size': Object.freeze({ size: 5.8, forwardAxis: '-Y', warningBeacon: false, visibleDroneDock: false }),
  'peterbilt-379-optimus-prime': Object.freeze({ size: 16.5, forwardAxis: '-Y', warningBeacon: false, visibleDroneDock: false }),
  'smart-city-drone': Object.freeze({ size: 2.6, forwardAxis: '-Y' }),
  'vtol-air-taxi': Object.freeze({ size: 11.5, forwardAxis: '-Y' })
})

export const ACTIVE_MODEL_ROLES = Object.freeze({
  ground_vehicle: 'tricycle',
  pace_vehicle: 'highway-patrol-cruiser',
  smart_drone: 'smart-city-drone'
})

export function getModelAsset(id) {
  const asset = MODEL_ASSETS[id]
  if (!asset) throw new Error(`Unknown 3D model asset: ${id}`)
  return asset
}

export function modelAssetPath(id) {
  return getModelAsset(id).path
}

export function modelAssetForRole(role) {
  const id = ACTIVE_MODEL_ROLES[role]
  if (!id) throw new Error(`No active 3D model is mapped to role: ${role}`)
  return getModelAsset(id)
}

export function mapPresentationForAsset(id) {
  getModelAsset(id)
  const presentation = MODEL_MAP_PRESENTATION[id]
  if (!presentation) throw new Error(`No map presentation is calibrated for model asset: ${id}`)
  return presentation
}
