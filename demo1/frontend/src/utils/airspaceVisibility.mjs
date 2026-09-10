function clampRatio(value) {
  const ratio = Number(value)
  return Number.isFinite(ratio) ? Math.max(0, Math.min(1, ratio)) : null
}

export function resolveAirspaceActivation(volume = {}, { planningPreview = false } = {}) {
  if (planningPreview || volume.planningPreview === true) return 1
  const state = String(volume.state || 'ACTIVE').toUpperCase()
  if (state === 'SCHEDULED' || state === 'EXPIRED') return 0
  if (state === 'ACTIVE' && volume.dynamic !== true) return 1
  const ratio = clampRatio(volume.activationRatio)
  if (state === 'ACTIVATING') return Math.max(.05, ratio ?? .05)
  return ratio ?? (state === 'ACTIVE' ? 1 : 0)
}
