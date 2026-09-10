const MISSION_PHASE_PRESENTATION = Object.freeze({
  DEPART: { id: 'DEPLOY', label: '装载出发', shortLabel: 'LOAD', description: '配送车与无人机完成装载并前往分拨点' },
  TAKEOFF: { id: 'TAKEOFF', label: '派车起飞', shortLabel: 'DISPATCH', description: '配送资源离开分拨点并进入计划路线' },
  DELIVERING: { id: 'DELIVERY', label: '运输配送', shortLabel: 'TRANSIT', description: '车辆与无人机沿计划路线执行订单配送' },
  RETURNING: { id: 'RETURN', label: '交付返程', shortLabel: 'DELIVER', description: '完成末端交付并返回回收位置' },
  DOCKED: { id: 'RECOVERY', label: '结算归档', shortLabel: 'SETTLE', description: '配送完成，等待经营结算与任务归档' }
})

const DEFAULT_SCHEDULE = Object.freeze({
  DEPART: [0, 10],
  TAKEOFF: [10, 20],
  DELIVERING: [20, 75],
  RETURNING: [75, 90],
  DOCKED: [90, 100]
})

export function normalizeMissionPhaseId(rawPhase) {
  const key = String(rawPhase || 'DOCKED').trim().toUpperCase()
  return MISSION_PHASE_PRESENTATION[key]?.id || key
}

export function phasePresentation(rawPhase) {
  const key = String(rawPhase || 'DOCKED').trim().toUpperCase()
  return MISSION_PHASE_PRESENTATION[key] || {
    id: key,
    label: key,
    shortLabel: key,
    description: ''
  }
}

export function normalizePhaseDefinitions(rawMission = {}) {
  const sourceSchedule = rawMission.phaseSchedule && typeof rawMission.phaseSchedule === 'object'
    ? rawMission.phaseSchedule
    : DEFAULT_SCHEDULE
  const entries = Object.entries(sourceSchedule)
    .map(([sourceId, range]) => {
      const start = Number(Array.isArray(range) ? range[0] : 0)
      const end = Number(Array.isArray(range) ? range[1] : start)
      return { sourceId: String(sourceId).toUpperCase(), start, end }
    })
    .filter(item => Number.isFinite(item.start) && Number.isFinite(item.end))
    .sort((a, b) => a.start - b.start)

  return entries.map((item, index) => ({
    ...phasePresentation(item.sourceId),
    sourceId: item.sourceId,
    order: index,
    range: [item.start, item.end]
  }))
}

export function phaseStateAtProgress(phase, activePhaseId, progress) {
  const current = Number(progress) || 0
  if (current >= Number(phase.range?.[1] ?? 0)) return 'COMPLETE'
  if (phase.id === activePhaseId) return 'ACTIVE'
  return 'PENDING'
}

export function phaseProgress(phase, missionProgress) {
  const start = Number(phase.range?.[0] ?? 0)
  const end = Number(phase.range?.[1] ?? start)
  if (end <= start) return Number(missionProgress) >= end ? 1 : 0
  return Math.max(0, Math.min(1, (Number(missionProgress) - start) / (end - start)))
}

export const missionPhasePresentation = MISSION_PHASE_PRESENTATION
