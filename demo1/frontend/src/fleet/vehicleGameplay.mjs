export function batteryTone(percent) {
  const value = Number(percent)
  if (!Number.isFinite(value)) return 'unknown'
  if (value <= 0) return 'depleted'
  if (value < 25) return 'red'
  if (value < 50) return 'orange'
  if (value < 75) return 'yellow'
  return 'green'
}

export function chargeRemainingSeconds(asset = {}, now = Date.now()) {
  if (!asset.charging || !asset.chargingCompletesAt) return 0
  const completesAt = Date.parse(asset.chargingCompletesAt)
  if (!Number.isFinite(completesAt)) return 0
  return Math.max(0, Math.ceil((completesAt - Number(now || 0)) / 1000))
}

export function projectedBatteryPercent(asset = {}, now = Date.now()) {
  const current = Number(asset.batteryPercent)
  if (!asset.charging || !asset.chargingStartedAt || !asset.chargingCompletesAt) return current
  const startedAt = Date.parse(asset.chargingStartedAt)
  const completesAt = Date.parse(asset.chargingCompletesAt)
  const from = Number(asset.chargingFromPercent)
  if (![current, from, startedAt, completesAt].every(Number.isFinite) || completesAt <= startedAt) return current
  // This interpolation only keeps the one-minute HUD visually alive;
  // deployment always rechecks the authoritative server value.
  const ratio = Math.max(0, Math.min(1, (Number(now) - startedAt) / (completesAt - startedAt)))
  return Math.max(current, from + (100 - from) * ratio)
}
