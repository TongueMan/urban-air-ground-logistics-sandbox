export const MISSION_TIME_SCALE_OPTIONS = Object.freeze([0.5, 1, 2, 5])

export function normalizeMissionTimeScale(value, fallback = 1) {
  const numeric = Number(value)
  return MISSION_TIME_SCALE_OPTIONS.includes(numeric) ? numeric : fallback
}

export function formatMissionSpeed(value) {
  if (value === null || value === undefined || value === '') return '—'
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${numeric.toFixed(1)} km/h` : '—'
}

export function isMissionReplayState(state) {
  return ['STOPPED', 'FAILED'].includes(String(state || '').trim().toUpperCase())
}
