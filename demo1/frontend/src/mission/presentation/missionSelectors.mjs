export function actorById(mission, actorId) {
  return mission?.actorsById?.[String(actorId || '')] || null
}

export function signalById(mission, signalId) {
  return mission?.signalsById?.[String(signalId || '')] || null
}

export function missionSummary(mission, focusedActorId = '') {
  const focused = actorById(mission, focusedActorId)
  const actors = focused ? [focused] : (mission?.actors || [])
  const drones = actors.filter(actor => actor.kind === 'UAV')
  const average = (field, fallback = null) => {
    const values = drones.map(actor => Number(actor.telemetry?.[field])).filter(Number.isFinite)
    return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : fallback
  }
  const deviations = actors.map(actor => Number(actor.telemetry?.routeDeviationMeters)).filter(Number.isFinite)
  return {
    coverage: average('deliveryProgressPercent', 0),
    battery: average('battery', focused?.kind === 'VEHICLE' ? Number(focused.telemetry?.battery) : null),
    link: average('linkQuality', focused ? Number(focused.telemetry?.linkQuality) : null),
    deviation: deviations.length ? Math.max(...deviations) : null
  }
}

export function groundVehicleSummary(mission) {
  const actor = (mission?.actors || []).find(item => item.kind === 'VEHICLE') || null
  const battery = Number(actor?.telemetry?.battery)
  const progress = Number(actor?.telemetry?.routeProgress)
  return {
    actor,
    name: mission?.groundVehicle?.name || actor?.name || '地面运输车',
    progress: Number.isFinite(progress) ? Math.max(0, Math.min(100, progress)) : 0,
    battery: Number.isFinite(battery) ? Math.max(0, Math.min(100, battery)) : null,
    depleted: mission?.terminalReason === 'GROUND_BATTERY_DEPLETED' || battery === 0
  }
}

export function airVehicleSummary(mission) {
  const actor = (mission?.actors || []).find(item => item.kind === 'UAV') || null
  const battery = Number(actor?.telemetry?.battery)
  return {
    actor,
    name: mission?.airVehicle?.name || actor?.name || '空中配送设备',
    battery: Number.isFinite(battery) ? Math.max(0, Math.min(100, battery)) : null,
    depleted: mission?.terminalReason === 'AIR_BATTERY_DEPLETED' || battery === 0
  }
}
