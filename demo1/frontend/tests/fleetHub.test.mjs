import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { MODEL_ASSETS } from '../src/config/modelAssets.mjs'
import { deployedFleetAssignments, effectiveFleetPrice, fleetCounts, fleetSections, formatCny } from '../src/fleet/fleetCatalog.mjs'
import { batteryTone, chargeRemainingSeconds, projectedBatteryPercent } from '../src/fleet/vehicleGameplay.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
const catalog = [
  ['tricycle', 'GROUND', 'tricycle', 800000],
  ['ford-f350-utility', 'GROUND', 'ford-f350-utility', 3600000],
  ['ural-truck-vehicle-only', 'GROUND', 'ural-truck-vehicle-only', 4800000],
  ['cybertruck-fun-size', 'GROUND', 'cybertruck-fun-size', 7600000],
  ['peterbilt-379-optimus-prime', 'GROUND', 'peterbilt-379-optimus-prime', 9800000],
  ['smart-city-drone', 'AIR', 'smart-city-drone', 2200000],
  ['vtol-air-taxi', 'AIR', 'vtol-air-taxi', 8800000]
].map(([typeId, category, modelAssetId, priceMinor]) => ({ typeId, category, modelAssetId, priceMinor }))

test('fleet catalog points every purchasable type to a registered model', () => {
  assert.equal(catalog.length, 7)
  for (const type of catalog) assert.ok(MODEL_ASSETS[type.modelAssetId], `${type.typeId} has no GLB registration`)
  assert.deepEqual(catalog.filter(type => type.category === 'GROUND').map(type => [type.typeId, type.priceMinor]), [
    ['tricycle', 800000],
    ['ford-f350-utility', 3600000],
    ['ural-truck-vehicle-only', 4800000],
    ['cybertruck-fun-size', 7600000],
    ['peterbilt-379-optimus-prime', 9800000]
  ])
})

test('initial fleet is grouped before unowned market models', () => {
  const snapshot = {
    catalog,
    assets: [
      { assetId: 'FLT-G', typeId: 'tricycle', status: 'GARAGED' },
      { assetId: 'FLT-A', typeId: 'smart-city-drone', status: 'GARAGED' }
    ]
  }
  const ground = fleetSections(snapshot, 'GROUND')
  const air = fleetSections(snapshot, 'AIR')
  assert.deepEqual(ground.owned.map(item => [item.typeId, item.count]), [['tricycle', 1]])
  assert.equal(ground.market.length, 4)
  assert.deepEqual(air.owned.map(item => item.typeId), ['smart-city-drone'])
  assert.deepEqual(air.market.map(item => item.typeId), ['vtol-air-taxi'])
})

test('fleet summaries and pricing use exact server states and minor units', () => {
  assert.deepEqual(fleetCounts([
    { status: 'GARAGED' }, { status: 'DEPLOYED' }, { status: 'GARAGED' }
  ]), { total: 3, garaged: 2, deployed: 1 })
  assert.equal(effectiveFleetPrice(catalog[0], false), 800000)
  assert.equal(effectiveFleetPrice(catalog[0], true), 0)
  assert.match(formatCny(10000000), /100,000/)
})

test('latest deployed assets become the current map models and VTOL stays independent', () => {
  const snapshot = {
    catalog: [
      ...catalog,
      { typeId: 'retired-unknown', category: 'GROUND', modelAssetId: 'tricycle' }
    ],
    assets: [
      { assetId: 'FLT-TRI', typeId: 'tricycle', status: 'DEPLOYED', updatedAt: '2026-09-08T08:00:00Z' },
      { assetId: 'FLT-PET', typeId: 'peterbilt-379-optimus-prime', status: 'DEPLOYED', updatedAt: '2026-09-08T08:02:00Z' },
      { assetId: 'FLT-UAV', typeId: 'smart-city-drone', status: 'DEPLOYED', updatedAt: '2026-09-08T08:00:00Z' },
      { assetId: 'FLT-VTOL', typeId: 'vtol-air-taxi', status: 'DEPLOYED', updatedAt: '2026-09-08T08:03:00Z' },
      { assetId: 'FLT-GARAGE', typeId: 'cybertruck-fun-size', status: 'GARAGED', updatedAt: '2026-09-08T08:04:00Z' }
    ]
  }
  const assignments = deployedFleetAssignments(snapshot)
  assert.deepEqual(assignments.ground_vehicle, {
    role: 'ground_vehicle',
    category: 'GROUND',
    assetId: 'FLT-PET',
    typeId: 'peterbilt-379-optimus-prime',
    modelAssetId: 'peterbilt-379-optimus-prime',
    name: undefined,
    independentRoute: false
  })
  assert.equal(assignments.smart_drone.assetId, 'FLT-VTOL')
  assert.equal(assignments.smart_drone.modelAssetId, 'vtol-air-taxi')
  assert.equal(assignments.smart_drone.independentRoute, true)
})

test('recalling a current model falls back to another deployed asset, then to no assignment', () => {
  const snapshot = {
    catalog,
    assets: [
      { assetId: 'FLT-TRI', typeId: 'tricycle', status: 'DEPLOYED', updatedAt: '2026-09-08T08:00:00Z' },
      { assetId: 'FLT-PET', typeId: 'peterbilt-379-optimus-prime', status: 'GARAGED', updatedAt: '2026-09-08T08:02:00Z' }
    ]
  }
  assert.equal(deployedFleetAssignments(snapshot).ground_vehicle.assetId, 'FLT-TRI')
  snapshot.assets[0].status = 'GARAGED'
  assert.equal(deployedFleetAssignments(snapshot).ground_vehicle, null)
  assert.equal(deployedFleetAssignments(snapshot).smart_drone, null)
})

test('fleet runtime replaces its snapshot from server responses', () => {
  const runtime = readFileSync(join(root, 'fleet', 'useFleetRuntime.js'), 'utf8')
  const api = readFileSync(join(root, 'api', 'demo.js'), 'utf8')
  assert.match(runtime, /snapshot\.value = Number\.isFinite/)
  assert.match(runtime, /mergeMissionEconomy/)
  assert.match(runtime, /incomingUpdatedAt < currentUpdatedAt/)
  assert.match(runtime, /replaceSnapshot\(result\.fleet\)/)
  assert.match(runtime, /sellFleetAsset/)
  assert.match(runtime, /commandId\('SELL'\)/)
  assert.match(api, /sellFleetAsset/)
  assert.match(api, /\/api\/demo\/fleet\/sales/)
  assert.doesNotMatch(runtime, /claimTutorialReward/)
  assert.doesNotMatch(api, /tutorial-rewards/)
  assert.doesNotMatch(runtime, /assets\.value\.push|balanceMinor\s*[-+]=|balanceMinor\s*=\s*balanceMinor/)
})

test('fleet hub confirms full-price sales and prevents deployed asset sales', () => {
  const hub = readFileSync(join(root, 'components', 'fleet', 'FleetHub.vue'), 'utf8')
  assert.match(hub, /确认按原价/)
  assert.match(hub, /selectedType\.priceMinor/)
  assert.match(hub, /售出后，该设备将从当前车队移除/)
  assert.match(hub, /asset\.status !== 'GARAGED'/)
  assert.match(hub, /请先召回车库后再出售/)
  assert.match(hub, /runtime\.sell\(sellingAsset\.value\)/)
  assert.match(hub, /saleConfirm\.value\?\.scrollIntoView/)
  assert.match(hub, /selectedType\.gameplayStats\.speedKph/)
  assert.match(hub, /selectedType\.gameplayStats\.fullRangeKm/)
  assert.match(hub, /selectedType\.gameplayStats\.cargoMultiplier/)
  assert.match(hub, /selectedType\.gameplayStats\.maneuverDelaySeconds/)
  assert.match(hub, /飞行速度/)
  assert.match(hub, /机动响应/)
  assert.match(hub, /充满还需/)
  assert.match(hub, /asset\.activeRunId/)
  assert.match(hub, /教程 00 引导/)
  assert.match(hub, /下一步/)
  assert.match(hub, /runtime\.guidance\.value/)
  assert.match(hub, /runtime\.dismissGuidance/)
})

test('battery colors use the five gameplay bands and charging projects linearly', () => {
  assert.equal(batteryTone(100), 'green')
  assert.equal(batteryTone(75), 'green')
  assert.equal(batteryTone(74.9), 'yellow')
  assert.equal(batteryTone(50), 'yellow')
  assert.equal(batteryTone(49.9), 'orange')
  assert.equal(batteryTone(25), 'orange')
  assert.equal(batteryTone(24.9), 'red')
  assert.equal(batteryTone(0.1), 'red')
  assert.equal(batteryTone(0), 'depleted')
  const asset = {
    batteryPercent: 20,
    chargingFromPercent: 20,
    charging: true,
    chargingStartedAt: '2026-09-09T00:00:00.000Z',
    chargingCompletesAt: '2026-09-09T00:01:00.000Z'
  }
  assert.equal(projectedBatteryPercent(asset, Date.parse('2026-09-09T00:00:30.000Z')), 60)
  assert.equal(chargeRemainingSeconds(asset, Date.parse('2026-09-09T00:00:30.000Z')), 30)
})

test('catalog cards remain renderer-free and hub owns one shared viewer', () => {
  const cards = readFileSync(join(root, 'components', 'fleet', 'FleetCardRail.vue'), 'utf8')
  const hub = readFileSync(join(root, 'components', 'fleet', 'FleetHub.vue'), 'utf8')
  const viewer = readFileSync(join(root, 'components', 'fleet', 'FleetModelViewer.vue'), 'utf8')
  assert.doesNotMatch(cards, /WebGLRenderer|GLTFLoader|<canvas/)
  assert.equal((hub.match(/<FleetModelViewer/g) || []).length, 1)
  assert.match(viewer, /new THREE\.WebGLRenderer/)
  assert.match(viewer, /animationDisabled = new Set\(\['smart-city-drone'\]\)/)
  assert.match(viewer, /!animationDisabled\.has\(id\)/)
  assert.match(viewer, /while \(cache\.size > 3\)/)
  assert.match(viewer, /Math\.min\(globalThis\.devicePixelRatio \|\| 1, 1\.5\)/)
})

test('running missions use both frozen ground and air model assignments', () => {
  const bridge = readFileSync(join(root, 'components', 'world', 'MissionWorldBridge.vue'), 'utf8')
  const dock = readFileSync(join(root, 'components', 'fleet', 'FleetDock.vue'), 'utf8')
  assert.match(bridge, /mission\.value\.airVehicle/)
  assert.match(bridge, /assignments\.smart_drone/)
  assert.match(dock, /mission\.value\.airVehicle/)
})
