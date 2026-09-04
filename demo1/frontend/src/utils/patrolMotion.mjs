const METERS_PER_DEGREE = 111000

export function distanceMeters(a, b) {
  const latitude = ((Number(a[1]) + Number(b[1])) / 2) * Math.PI / 180
  const dx = (Number(b[0]) - Number(a[0])) * METERS_PER_DEGREE * Math.cos(latitude)
  const dy = (Number(b[1]) - Number(a[1])) * METERS_PER_DEGREE
  const dz = Number(b[2] || 0) - Number(a[2] || 0)
  return Math.hypot(dx, dy, dz)
}

export function bearingDegrees(a, b) {
  const latitude = ((Number(a[1]) + Number(b[1])) / 2) * Math.PI / 180
  const east = (Number(b[0]) - Number(a[0])) * Math.cos(latitude)
  const north = Number(b[1]) - Number(a[1])
  return (Math.atan2(east, north) * 180 / Math.PI + 360) % 360
}

export function createPolylineSampler(source = []) {
  const points = source.map(point => point.map(Number)).filter(point => point.length >= 2 && point.every(Number.isFinite))
  const cumulative = [0]
  for (let index = 1; index < points.length; index += 1) cumulative.push(cumulative[index - 1] + distanceMeters(points[index - 1], points[index]))
  const total = cumulative.at(-1) || 0
  function locate(progress) {
    if (!points.length) return { coordinate: [0, 0, 0], heading: 0, segment: 0 }
    if (points.length === 1 || total <= 0) return { coordinate: points[0].slice(), heading: 0, segment: 0 }
    const target = Math.max(0, Math.min(1, Number(progress) || 0)) * total
    let segment = 0
    while (segment < cumulative.length - 2 && cumulative[segment + 1] < target) segment += 1
    const length = cumulative[segment + 1] - cumulative[segment]
    const ratio = length <= 0 ? 0 : (target - cumulative[segment]) / length
    const from = points[segment], to = points[segment + 1]
    const coordinate = [0, 1, 2].map(axis => Number(from[axis] || 0) + (Number(to[axis] || 0) - Number(from[axis] || 0)) * ratio)
    const before = points[Math.max(0, segment - 1)]
    const after = points[Math.min(points.length - 1, segment + 2)]
    return { coordinate, heading: bearingDegrees(before, after), segment }
  }
  function slice(progress) {
    if (points.length < 2) return points.map(point => point.slice())
    const located = locate(progress)
    return [...points.slice(0, located.segment + 1).map(point => point.slice()), located.coordinate]
  }
  return { points, total, locate, slice }
}

function catmullCoordinate(p0, p1, p2, p3, t) {
  const t2 = t * t, t3 = t2 * t
  return [0, 1, 2].map(axis => 0.5 * (
    2 * Number(p1[axis] || 0)
    + (-Number(p0[axis] || 0) + Number(p2[axis] || 0)) * t
    + (2 * Number(p0[axis] || 0) - 5 * Number(p1[axis] || 0) + 4 * Number(p2[axis] || 0) - Number(p3[axis] || 0)) * t2
    + (-Number(p0[axis] || 0) + 3 * Number(p1[axis] || 0) - 3 * Number(p2[axis] || 0) + Number(p3[axis] || 0)) * t3
  ))
}

export function densifyCatmullRom(source = [], samplesPerSegment = 18) {
  const points = source.map(point => point.map(Number)).filter(point => point.length >= 2 && point.every(Number.isFinite))
  if (points.length < 3) return points
  const result = []
  for (let index = 0; index < points.length - 1; index += 1) {
    const p0 = points[Math.max(0, index - 1)], p1 = points[index]
    const p2 = points[index + 1], p3 = points[Math.min(points.length - 1, index + 2)]
    for (let step = 0; step < samplesPerSegment; step += 1) result.push(catmullCoordinate(p0, p1, p2, p3, step / samplesPerSegment))
  }
  result.push(points.at(-1).slice())
  return result
}

export function hasMissionSimulation(mission = {}) {
  const simulationId = String(mission?.simulationId || '').trim()
  const state = String(mission?.state || '').trim().toUpperCase()
  return Boolean(simulationId) && state !== 'STANDBY'
}

export function selectMissionActualPoints(route = {}, mission = {}, replayPercent = 100) {
  if (!hasMissionSimulation(mission)) return []
  let points = Array.isArray(route?.actualPoints) ? route.actualPoints : []
  if (String(route?.kind || '').toUpperCase() === 'AIR') {
    points = points.filter(point => {
      const metrics = point?.metrics || {}
      const phase = String(metrics.missionPhase || '').toUpperCase()
      const segment = String(metrics.trackSegment || '').toUpperCase()
      return phase !== 'DEPART' && segment !== 'CARRY_TO_LAUNCH'
    })
  }
  const state = String(mission?.state || '').toUpperCase()
  if (!['STOPPED', 'FAILED'].includes(state)) return points
  const percent = Math.max(1, Math.min(100, Number(replayPercent) || 100))
  return points.slice(0, Math.max(1, Math.ceil(points.length * percent / 100)))
}

export function patrolModelPhaseState(type, phase) {
  const normalizedType = String(type || '').toLowerCase()
  const normalizedPhase = String(phase || '').toUpperCase()
  const isDrone = normalizedType === 'smart_drone'
  const airborne = isDrone && ['TAKEOFF', 'SCANNING', 'RETURNING'].includes(normalizedPhase)
  const docked = isDrone && ['DEPART', 'DOCKED'].includes(normalizedPhase)
  return {
    airborne,
    docked,
    rotorActive: airborne,
    showAltitude: airborne,
    showGroundRing: !isDrone || airborne,
    showHalo: !isDrone || airborne
  }
}

export function modelVisibilityScale(type, distance, fovDegrees, viewportHeight, options = {}) {
  const isDrone = String(type || '').toLowerCase() === 'smart_drone'
  const phase = String(options.phase || '').toUpperCase()
  const airborne = isDrone && ['TAKEOFF', 'SCANNING', 'RETURNING'].includes(phase)
  const takeoffProgress = Math.max(0, Math.min(1, Number(options.takeoffProgress) || 0))
  const takeoffEase = takeoffProgress * takeoffProgress * (3 - 2 * takeoffProgress)
  const physicalSize = isDrone ? 2.6 : 5.25
  const droneTargetPixels = phase === 'TAKEOFF' ? 28 + 18 * takeoffEase : airborne ? 46 : 28
  const targetPixels = isDrone ? droneTargetPixels : 46
  const minimumScale = isDrone
    ? phase === 'TAKEOFF' ? 1.08 + .82 * takeoffEase : airborne ? 1.9 : 1.08
    : 1.72
  const maxScale = isDrone ? 210 : 145
  const safeDistance = Math.max(1, Number(distance) || 1)
  const safeHeight = Math.max(1, Number(viewportHeight) || 1)
  const safeFov = Math.max(10, Math.min(120, Number(fovDegrees) || 50))
  const worldUnitsPerPixel = 2 * safeDistance * Math.tan(safeFov * Math.PI / 360) / safeHeight
  const screenScale = targetPixels * worldUnitsPerPixel / physicalSize
  const selectedFactor = options.selected ? 1.12 : 1
  return Math.min(maxScale, Math.max(minimumScale, screenScale) * selectedFactor)
}

export function patrolFollowCamera(type, heading, mode = 'rear') {
  const isDrone = String(type || '').toLowerCase() === 'smart_drone'
  const normalizedMode = mode === 'side' ? 'side' : 'rear'
  // MapV 的 lookAt heading 表示观察方位而不是模型自身朝向：同向值呈侧视，
  // 向右后方旋转约 72° 才能沿道路纵向看到设备的后方。
  const headingOffset = normalizedMode === 'side' ? 0 : -72
  return {
    heading: (Number(heading || 0) + headingOffset + 360) % 360,
    pitch: normalizedMode === 'side' ? 54 : 59,
    range: isDrone
      ? normalizedMode === 'side' ? 112 : 96
      : normalizedMode === 'side' ? 86 : 72
  }
}

export function adjustFollowZoomScale(currentScale, deltaY) {
  const scale = Number.isFinite(Number(currentScale)) ? Number(currentScale) : 1
  const wheelDelta = Math.max(-480, Math.min(480, Number(deltaY) || 0))
  return Math.max(.65, Math.min(4, scale * Math.exp(wheelDelta * .00135)))
}

export function shortestHeadingDelta(from, to) {
  return ((Number(to) - Number(from) + 540) % 360) - 180
}

export function interpolateHeading(from, to, ratio) {
  return (Number(from) + shortestHeadingDelta(from, to) * Math.max(0, Math.min(1, ratio)) + 360) % 360
}
