import { deployedFleetAssignments } from './fleetCatalog.mjs'
import { projectedBatteryPercent } from './vehicleGameplay.mjs'

const LIVE_MISSION_STATES = new Set(['RUNNING', 'QUEUED'])
const TERMINAL_MISSION_STATES = new Set(['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'])

function finiteVersion(value) {
  const version = Number(value)
  return Number.isFinite(version) ? version : null
}

/**
 * Resolve the authoritative fleet state shown by the idle/preview instrument.
 * A running mission keeps using mission telemetry because the fleet snapshot is
 * not refreshed on every simulation tick. Outside a live run, fleet mutations
 * and the one-minute charging projection should replace the frozen preview.
 */
export function instrumentFleetVehicleState(mission = {}, snapshot = {}, category = 'GROUND', now = Date.now()) {
  const status = String(mission?.status || '').toUpperCase()
  if (LIVE_MISSION_STATES.has(status)) return null

  const normalizedCategory = category === 'AIR' ? 'AIR' : 'GROUND'
  const vehicleKey = normalizedCategory === 'AIR' ? 'airVehicle' : 'groundVehicle'
  const assignmentKey = normalizedCategory === 'AIR' ? 'smart_drone' : 'ground_vehicle'
  const preferredAssetId = String(mission?.[vehicleKey]?.assetId || '')
  const assets = Array.isArray(snapshot?.assets) ? snapshot.assets : []
  const catalog = Array.isArray(snapshot?.catalog) ? snapshot.catalog : []
  const assignment = deployedFleetAssignments(snapshot)?.[assignmentKey]
  const assignedAsset = assignment?.assetId
    ? assets.find(asset => String(asset.assetId) === String(assignment.assetId))
    : null
  const preferredAsset = preferredAssetId
    ? assets.find(asset => String(asset.assetId) === preferredAssetId)
    : null
  const asset = assignedAsset || preferredAsset
  if (!asset) return null

  // A terminal mission can outlive a fleet snapshot captured before or during
  // its run. Keep the final telemetry until a later fleet mutation or refresh
  // both clears activeRunId and proves the asset is newer than the assignment.
  if (TERMINAL_MISSION_STATES.has(status) && mission?.source?.session?.id
      && String(asset.assetId || '') === preferredAssetId) {
    const fleetVersion = finiteVersion(asset.stateVersion)
    const missionVersion = finiteVersion(mission?.[vehicleKey]?.stateVersion)
    if (asset.activeRunId || fleetVersion == null || missionVersion == null || fleetVersion <= missionVersion) return null
  }

  const batteryPercent = Number(projectedBatteryPercent(asset, now))
  if (!Number.isFinite(batteryPercent)) return null
  const type = catalog.find(item => String(item.typeId) === String(asset.typeId)) || null
  return {
    assetId: String(asset.assetId || ''),
    batteryPercent: Math.max(0, Math.min(100, batteryPercent)),
    charging: Boolean(asset.charging),
    name: type?.name || assignment?.name || ''
  }
}
