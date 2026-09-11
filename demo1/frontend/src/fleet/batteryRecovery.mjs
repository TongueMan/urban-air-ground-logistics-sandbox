const RECOVERY_BY_REASON = Object.freeze({
  GROUND_BATTERY_DEPLETED: { category: 'GROUND', fallbackName: '地面车辆' },
  AIR_BATTERY_DEPLETED: { category: 'AIR', fallbackName: '空中设备' }
})

const TERMINAL_STATUSES = new Set(['FAILED', 'STOPPED', 'EXPIRED'])

export function batteryRecoveryForMission(mission = {}) {
  const status = String(mission?.status || '').toUpperCase()
  const terminalReason = String(mission?.terminalReason || '').toUpperCase()
  const policy = RECOVERY_BY_REASON[terminalReason]
  if (!policy || !TERMINAL_STATUSES.has(status)) return null

  const vehicle = policy.category === 'GROUND' ? mission?.groundVehicle : mission?.airVehicle
  const sourceSession = mission?.source?.session || {}
  const assetId = String(vehicle?.assetId
    || (policy.category === 'GROUND' ? sourceSession.groundAssetId : sourceSession.airAssetId)
    || '')
  const deviceName = String(vehicle?.name || policy.fallbackName)

  return {
    code: terminalReason,
    category: policy.category,
    assetId,
    deviceName,
    title: `${deviceName}电量已耗尽，任务已停止`,
    message: '请前往车队中心召回设备；返回车库后将自动充电。',
    actionLabel: '前往车队中心'
  }
}
