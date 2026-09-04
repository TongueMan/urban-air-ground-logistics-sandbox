export const PATROL_TIME_SCALE_OPTIONS = Object.freeze([0.5, 1, 2, 5])

export function normalizePatrolTimeScale(value, fallback = 1) {
  const numeric = Number(value)
  return PATROL_TIME_SCALE_OPTIONS.includes(numeric) ? numeric : fallback
}

export function formatPatrolSpeed(value) {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${numeric.toFixed(1)} km/h` : '—'
}

export function isPatrolReplayState(state) {
  return ['STOPPED', 'FAILED'].includes(String(state || '').trim().toUpperCase())
}
