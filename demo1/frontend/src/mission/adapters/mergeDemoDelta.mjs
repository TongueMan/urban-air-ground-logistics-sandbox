function revisionOf(value) {
  const revision = Number(value?.revision)
  return Number.isFinite(revision) ? revision : null
}

export function mergeDemoDelta(snapshot, eventType, delta) {
  if (!snapshot || !delta) return null
  const currentRevision = revisionOf(snapshot)
  const nextRevision = revisionOf(delta)
  if (nextRevision == null) return null
  if (currentRevision != null && nextRevision <= currentRevision) return snapshot
  if (currentRevision != null && nextRevision !== currentRevision + 1) return null

  const next = { ...snapshot, revision: nextRevision }
  if (eventType === 'mission-delta') {
    if (delta.session) next.session = { ...(snapshot.session || {}), ...delta.session }
    if (delta.mission) next.mission = { ...(snapshot.mission || {}), ...delta.mission }
    if (Array.isArray(delta.signals)) next.signals = delta.signals
    return next
  }
  if (eventType === 'track-delta') {
    if (!delta.device?.deviceId || !delta.point) return null
    const devices = Array.isArray(snapshot.devices) ? [...snapshot.devices] : []
    const deviceIndex = devices.findIndex(device => String(device.deviceId) === String(delta.device.deviceId))
    if (deviceIndex >= 0) devices[deviceIndex] = delta.device
    else devices.push(delta.device)
    const mission = { ...(snapshot.mission || {}) }
    mission.routes = (mission.routes || []).map(route => {
      if (String(route.deviceId) !== String(delta.device.deviceId)) return route
      const actualPoints = [...(route.actualPoints || []), delta.point].slice(-500)
      return { ...route, actualPoints }
    })
    next.devices = devices
    next.mission = mission
    return next
  }
  if (eventType === 'signal-delta') {
    if (!delta.signal?.id) return null
    const signals = Array.isArray(snapshot.signals) ? [...snapshot.signals] : []
    const signalIndex = signals.findIndex(signal => String(signal.id) === String(delta.signal.id))
    if (signalIndex >= 0) signals[signalIndex] = delta.signal
    else signals.push(delta.signal)
    next.signals = signals
    return next
  }
  if (eventType === 'command-ack') return next
  if (eventType === 'ground-route-command' || eventType === 'ground-route-delta') {
    if (!delta.groundRouting) return eventType === 'ground-route-command' ? next : null
    next.mission = { ...(snapshot.mission || {}), groundRouting: delta.groundRouting }
    return next
  }
  if (eventType === 'pace-vehicle-delta') {
    if (!delta.paceVehicle) return null
    next.mission = { ...(snapshot.mission || {}), paceVehicle: delta.paceVehicle }
    return next
  }
  if (eventType === 'tutorial-progress') return next
  if (eventType === 'airspace-delta') {
    if (!delta.airspace) return null
    next.mission = { ...(snapshot.mission || {}), airspace: delta.airspace }
    return next
  }
  if (eventType === 'airspace-warning') {
    if (!delta.airspace || !delta.warning?.volumeId) return null
    if (delta.session) next.session = { ...(snapshot.session || {}), ...delta.session }
    next.mission = { ...(snapshot.mission || {}), airspace: delta.airspace }
    return next
  }
  if (eventType === 'rewind-checkpoint') {
    if (!delta.timeline) return null
    next.mission = { ...(snapshot.mission || {}), timeline: delta.timeline }
    return next
  }
  if (eventType === 'economy-delta') {
    if (!delta.economy) return null
    next.mission = { ...(snapshot.mission || {}), economy: delta.economy }
    return next
  }
  return null
}
