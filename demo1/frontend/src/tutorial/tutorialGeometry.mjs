export function normalizeRect(rect) {
  if (!rect) return null
  const left = Number(rect.left || 0)
  const top = Number(rect.top || 0)
  const width = Number(rect.width ?? Math.max(0, Number(rect.right || 0) - left))
  const height = Number(rect.height ?? Math.max(0, Number(rect.bottom || 0) - top))
  return { left, top, width, height, right: left + width, bottom: top + height }
}

export function expandRect(rect, padding = 12, viewport = {}) {
  const value = normalizeRect(rect)
  if (!value) return null
  const maxWidth = Number(viewport.width || globalThis.innerWidth || value.right + padding)
  const maxHeight = Number(viewport.height || globalThis.innerHeight || value.bottom + padding)
  const left = Math.max(0, value.left - padding)
  const top = Math.max(0, value.top - padding)
  const right = Math.min(maxWidth, value.right + padding)
  const bottom = Math.min(maxHeight, value.bottom + padding)
  return { left, top, right, bottom, width: right - left, height: bottom - top }
}

export function rectsOverlap(firstRect, secondRect, padding = 0) {
  const first = normalizeRect(firstRect)
  const second = normalizeRect(secondRect)
  if (!first || !second) return false
  return !(first.right + padding <= second.left || first.left - padding >= second.right
    || first.bottom + padding <= second.top || first.top - padding >= second.bottom)
}

export function shouldAvoidDialogueTarget(targetRect, dialogueRect, viewport = {}) {
  const target = normalizeRect(targetRect)
  const dialogue = normalizeRect(dialogueRect)
  if (!target || !dialogue) return false
  const width = Number(viewport.width || globalThis.innerWidth || 0)
  const height = Number(viewport.height || globalThis.innerHeight || 0)
  if (width && height && target.width * target.height > width * height * .7) return false
  return rectsOverlap(target, dialogue, 8)
}

export function calculateConnectorPath(targetRect, sourceRect, viewport = {}) {
  const target = normalizeRect(targetRect)
  const source = normalizeRect(sourceRect)
  if (!target || !source) return ''
  const targetCenter = { x: target.left + target.width / 2, y: target.top + target.height / 2 }
  const sourceCenter = { x: source.left + source.width / 2, y: source.top + source.height / 2 }
  const dx = targetCenter.x - sourceCenter.x
  const dy = targetCenter.y - sourceCenter.y
  let start
  let end
  let control1
  let control2

  if (Math.abs(dx) > Math.abs(dy) * 0.72) {
    const direction = Math.sign(dx) || 1
    start = { x: direction > 0 ? source.right : source.left, y: sourceCenter.y }
    end = { x: direction > 0 ? target.left : target.right, y: targetCenter.y }
    const bend = Math.max(48, Math.min(180, Math.abs(end.x - start.x) * 0.42))
    control1 = { x: start.x + direction * bend, y: start.y }
    control2 = { x: end.x - direction * bend, y: end.y }
  } else {
    const direction = Math.sign(dy) || -1
    start = { x: sourceCenter.x, y: direction > 0 ? source.bottom : source.top }
    end = { x: targetCenter.x, y: direction > 0 ? target.top : target.bottom }
    const bend = Math.max(48, Math.min(180, Math.abs(end.y - start.y) * 0.42))
    control1 = { x: start.x, y: start.y + direction * bend }
    control2 = { x: end.x, y: end.y - direction * bend }
  }

  const clamp = (value, maximum) => Math.max(0, Math.min(Number(maximum || value), value))
  const w = Number(viewport.width || globalThis.innerWidth || 99999)
  const h = Number(viewport.height || globalThis.innerHeight || 99999)
  const points = [start, control1, control2, end].map(point => ({ x: clamp(point.x, w), y: clamp(point.y, h) }))
  return `M ${points[0].x.toFixed(1)} ${points[0].y.toFixed(1)} C ${points[1].x.toFixed(1)} ${points[1].y.toFixed(1)}, ${points[2].x.toFixed(1)} ${points[2].y.toFixed(1)}, ${points[3].x.toFixed(1)} ${points[3].y.toFixed(1)}`
}
