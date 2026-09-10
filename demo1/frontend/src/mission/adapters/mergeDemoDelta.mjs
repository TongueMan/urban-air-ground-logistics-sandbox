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
  if (eventType === 'advisory-delta') {
    if (!delta.advisory?.id) return null
    const advisories = Array.isArray(snapshot.advisories) ? [...snapshot.advisories] : []
    const index = advisories.findIndex(item => String(item.id) === String(delta.advisory.id))
    if (index >= 0) advisories[index] = delta.advisory
    else advisories.push(delta.advisory)
    next.advisories = advisories.slice(-20)
    return next
  }
  if (eventType === 'decision-ack') {
    if (!delta.decision?.id) return null
    const decisions = Array.isArray(snapshot.decisions) ? [...snapshot.decisions] : []
    const index = decisions.findIndex(item => String(item.id) === String(delta.decision.id))
    if (index >= 0) decisions[index] = delta.decision
    else decisions.push(delta.decision)
    next.decisions = decisions.slice(-20)
    const mission = { ...(snapshot.mission || {}) }
    const previous = mission.decisionSummary || {}
    const uniqueAdvisories = new Set(next.decisions.map(item => item.advisoryId))
    mission.decisionSummary = {
      ...previous,
      decisionCount: next.decisions.length,
      approvedCount: next.decisions.filter(item => item.status === 'PLAN_APPROVED').length,
      rejectedCount: next.decisions.filter(item => item.status === 'REJECTED').length,
      pendingCount: Math.max(0, Number(previous.advisoryCount || 0) - uniqueAdvisories.size),
      executionBlocked: next.decisions.some(item => item.status === 'PLAN_APPROVED'),
      latestDecision: delta.decision
    }
    next.mission = mission
    return next
  }
  return null
}
