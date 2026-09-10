export const TIMELINE_SNAP_ENTER_PERCENT = 1.5
export const TIMELINE_SNAP_RELEASE_PERCENT = 2.5

function clamp(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value))
}

export function snapTimelineProgress(rawValue, checkpoints = [], options = {}) {
  const liveProgress = clamp(Number(options.liveProgress) || 0, 0, 100)
  const value = clamp(Number(rawValue) || 0, 0, liveProgress)
  const enterThreshold = Math.max(0, Number(options.enterThreshold ?? TIMELINE_SNAP_ENTER_PERCENT))
  const releaseThreshold = Math.max(enterThreshold, Number(options.releaseThreshold ?? TIMELINE_SNAP_RELEASE_PERCENT))
  const activeCheckpointId = String(options.activeCheckpointId || '')
  const points = checkpoints
    .map(checkpoint => ({ checkpoint, id: String(checkpoint?.id || ''), progress: Number(checkpoint?.progress) }))
    .filter(point => point.id && Number.isFinite(point.progress) && point.progress >= 0 && point.progress <= liveProgress)

  const active = points.find(point => point.id === activeCheckpointId)
  if (active && Math.abs(active.progress - value) <= releaseThreshold) {
    return { value: active.progress, checkpointId: active.id, checkpoint: active.checkpoint, snapped: true }
  }

  const nearest = points.reduce((best, point) => {
    const distance = Math.abs(point.progress - value)
    return !best || distance < best.distance ? { ...point, distance } : best
  }, null)
  if (nearest && nearest.distance <= enterThreshold) {
    return { value: nearest.progress, checkpointId: nearest.id, checkpoint: nearest.checkpoint, snapped: true }
  }
  return { value, checkpointId: '', checkpoint: null, snapped: false }
}
