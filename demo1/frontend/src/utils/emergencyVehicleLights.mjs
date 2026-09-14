const DOUBLE_FLASH_SLOT_MS = 120
const DOUBLE_FLASH_SLOTS = 8
const LIGHT_ON = Object.freeze({ emissiveIntensity: 12, coverOpacity: .82, glowOpacity: .92, pointIntensity: 8 })
const LIGHT_OFF = Object.freeze({ emissiveIntensity: .16, coverOpacity: .16, glowOpacity: .06, pointIntensity: 0 })
const LIGHT_STEADY = Object.freeze({ emissiveIntensity: 3.6, coverOpacity: .56, glowOpacity: .42, pointIntensity: 2.2 })
const STEADY_FRAME = Object.freeze({ red: LIGHT_STEADY, blue: LIGHT_STEADY })

export function emergencyLightSide(materialName) {
  const name = String(materialName || '').toLowerCase()
  if (name.includes('red_light') || name.includes('red_cone')) return 'red'
  if (name.includes('blue_light') || name.includes('blue_cone')) return 'blue'
  return null
}

export function emergencyLightFrame(nowMs, reducedMotion = false) {
  if (reducedMotion) {
    return STEADY_FRAME
  }

  const cycleMs = DOUBLE_FLASH_SLOT_MS * DOUBLE_FLASH_SLOTS
  const normalized = ((Number(nowMs) || 0) % cycleMs + cycleMs) % cycleMs
  const slot = Math.floor(normalized / DOUBLE_FLASH_SLOT_MS)
  const redOn = slot === 0 || slot === 2
  const blueOn = slot === 4 || slot === 6
  return { red: redOn ? LIGHT_ON : LIGHT_OFF, blue: blueOn ? LIGHT_ON : LIGHT_OFF }
}
