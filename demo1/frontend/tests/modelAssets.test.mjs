import assert from 'node:assert/strict'
import test from 'node:test'
import * as THREE from 'three'
import {
  ACTIVE_MODEL_ROLES,
  MODEL_ASSETS,
  MODEL_MAP_PRESENTATION,
  mapPresentationForAsset,
  modelAssetForRole
} from '../src/config/modelAssets.mjs'
import { centerSceneForTransform } from '../src/utils/modelSceneTransforms.mjs'

test('3D asset library catalogues all current and supplied models', () => {
  assert.deepEqual(Object.keys(MODEL_ASSETS).sort(), [
    'cybertruck-fun-size',
    'ford-f350-utility',
    'gold-coin',
    'peterbilt-379-optimus-prime',
    'pink-diamond',
    'smart-city-drone',
    'tricycle',
    'ural-truck-vehicle-only',
    'vtol-air-taxi'
  ])
  assert.equal(new Set(Object.values(MODEL_ASSETS).map(asset => asset.path)).size, 9)
})

test('runtime roles only map to active catalog assets', () => {
  assert.deepEqual(ACTIVE_MODEL_ROLES, {
    ground_vehicle: 'tricycle',
    smart_drone: 'smart-city-drone'
  })
  assert.equal(modelAssetForRole('ground_vehicle').lifecycle, 'active')
  assert.equal(modelAssetForRole('smart_drone').lifecycle, 'active')
})

test('every model has an audited horizontal front axis for map heading', () => {
  const mapModels = Object.values(MODEL_ASSETS).filter(asset => asset.category !== 'reward').map(asset => asset.id).sort()
  assert.deepEqual(Object.keys(MODEL_MAP_PRESENTATION).sort(), mapModels)
  assert.deepEqual(Object.fromEntries(Object.entries(MODEL_MAP_PRESENTATION).map(([id, value]) => [id, value.forwardAxis])), {
    tricycle: '+X',
    'ford-f350-utility': '+Y',
    'ural-truck-vehicle-only': '-Y',
    'cybertruck-fun-size': '-Y',
    'peterbilt-379-optimus-prime': '-Y',
    'smart-city-drone': '-Y',
    'vtol-air-taxi': '-Y'
  })
  for (const id of mapModels) assert.equal(mapPresentationForAsset(id), MODEL_MAP_PRESENTATION[id])
})

test('license and active-use metadata remain explicit', () => {
  assert.equal(MODEL_ASSETS['gold-coin'].license, 'CC-BY-4.0')
  assert.equal(MODEL_ASSETS['gold-coin'].sha256, 'f870f7744f8f393498cf937c9e5c6b37338bad2a74236f0355b293229b21a31f')
  assert.equal(MODEL_ASSETS['pink-diamond'].license, 'CC-BY-4.0')
  assert.equal(MODEL_ASSETS['pink-diamond'].sha256, 'dc1bfdf76112c9951eb2fdc7c0b86dc03a4b2d91c794348f2bce116221ed06ce')
  assert.equal(MODEL_ASSETS['cybertruck-fun-size'].license, 'CC-BY-NC-4.0')
  assert.equal(MODEL_ASSETS['cybertruck-fun-size'].lifecycle, 'active')
  assert.equal(MODEL_ASSETS['smart-city-drone'].license, 'NON-COMMERCIAL')
  assert.equal(MODEL_ASSETS['smart-city-drone'].sha256, '3647feb65c73875e9ba5b2a33374f506e3ed3eac1d1050f7f3156065239f7b6d')
  assert.equal(MODEL_ASSETS.tricycle.license, 'CC-BY-4.0')
  assert.equal(MODEL_ASSETS['vtol-air-taxi'].lifecycle, 'active')
})

test('processed vehicle assets record their optimization provenance', () => {
  const peterbilt = MODEL_ASSETS['peterbilt-379-optimus-prime']
  const cybertruck = MODEL_ASSETS['cybertruck-fun-size']
  const ural = MODEL_ASSETS['ural-truck-vehicle-only']
  assert.ok(peterbilt.bytes < peterbilt.optimization.originalBytes / 10)
  assert.ok(peterbilt.extensions.includes('EXT_meshopt_compression'))
  assert.equal(cybertruck.optimization.originalObjects, 24)
  assert.equal(cybertruck.optimization.vehicleObjects, 20)
  assert.equal(cybertruck.scene.animations, 0)
  assert.ok(cybertruck.bytes < cybertruck.optimization.originalBytes / 2)
  assert.ok(cybertruck.extensions.includes('EXT_meshopt_compression'))
  assert.equal(ural.optimization.originalObjects, 35)
  assert.equal(ural.optimization.vehicleObjects, 20)
  assert.match(ural.path, /vehicle-only\.glb$/)
})

test('imported model content stays centred while its transform root scales and spins', () => {
  const scene = new THREE.Group()
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(4, 2, 6))
  mesh.position.set(1400, -580, 1080)
  scene.add(mesh)
  const center = new THREE.Box3().setFromObject(scene).getCenter(new THREE.Vector3())
  const transformRoot = centerSceneForTransform(scene, center)

  transformRoot.scale.setScalar(.001)
  transformRoot.rotation.z = Math.PI * .37
  transformRoot.updateMatrixWorld(true)

  const transformedCenter = new THREE.Box3().setFromObject(transformRoot).getCenter(new THREE.Vector3())
  assert.ok(transformedCenter.length() < 1e-9)
})
