const METERS_PER_DEGREE = 111000

export function distanceMeters(a, b) {
  const latitude = ((Number(a[1]) + Number(b[1])) / 2) * Math.PI / 180
  const dx = (Number(b[0]) - Number(a[0])) * METERS_PER_DEGREE * Math.cos(latitude)
  const dy = (Number(b[1]) - Number(a[1])) * METERS_PER_DEGREE
  const dz = Number(b[2] || 0) - Number(a[2] || 0)
  return Math.hypot(dx, dy, dz)
}

export function horizontalDistanceMeters(a, b) {
  const latitude = ((Number(a?.[1]) + Number(b?.[1])) / 2) * Math.PI / 180
  const dx = (Number(b?.[0]) - Number(a?.[0])) * METERS_PER_DEGREE * Math.cos(latitude)
  const dy = (Number(b?.[1]) - Number(a?.[1])) * METERS_PER_DEGREE
  return Math.hypot(dx, dy)
}

export function bearingDegrees(a, b) {
  const latitude = ((Number(a[1]) + Number(b[1])) / 2) * Math.PI / 180
  const east = (Number(b[0]) - Number(a[0])) * Math.cos(latitude)
  const north = Number(b[1]) - Number(a[1])
  return (Math.atan2(east, north) * 180 / Math.PI + 360) % 360
}

export function movementHeadingDegrees(from, to, fallback = 0, minimumDistanceMeters = .02) {
  const safeFallback = Number.isFinite(Number(fallback)) ? Number(fallback) : 0
  if (!Array.isArray(from) || !Array.isArray(to)) return safeFallback
  const distance = horizontalDistanceMeters(from, to)
  if (!Number.isFinite(distance) || distance < Math.max(0, Number(minimumDistanceMeters) || 0)) return safeFallback
  return bearingDegrees(from, to)
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
    // Heading must be the tangent of the segment the object is actually on.
    // Looking across neighbouring segments makes right-angle and hairpin routes
    // point diagonally or backwards, so only skip genuinely duplicate points.
    let headingFrom = segment, headingTo = segment + 1
    while (headingTo < points.length && horizontalDistanceMeters(points[headingFrom], points[headingTo]) < .02) headingTo += 1
    if (headingTo >= points.length) {
      headingTo = Math.min(points.length - 1, segment + 1)
      headingFrom = segment
      while (headingFrom > 0 && horizontalDistanceMeters(points[headingFrom], points[headingTo]) < .02) headingFrom -= 1
    }
    return { coordinate, heading: movementHeadingDegrees(points[headingFrom], points[headingTo], 0), segment }
  }
  function slice(progress) {
    if (points.length < 2) return points.map(point => point.slice())
    const located = locate(progress)
    return [...points.slice(0, located.segment + 1).map(point => point.slice()), located.coordinate]
  }
  return { points, total, locate, slice }
}

function projectOntoPolyline(points, target) {
  if (!Array.isArray(target) || target.length < 2 || !target.every(value => Number.isFinite(Number(value)))) return null
  let best = null
  let distanceAlong = 0
  for (let index = 0; index < points.length - 1; index += 1) {
    const from = points[index], to = points[index + 1]
    const latitude = ((Number(from[1]) + Number(to[1]) + Number(target[1])) / 3) * Math.PI / 180
    const longitudeScale = METERS_PER_DEGREE * Math.cos(latitude)
    const ax = Number(from[0]) * longitudeScale, ay = Number(from[1]) * METERS_PER_DEGREE
    const bx = Number(to[0]) * longitudeScale, by = Number(to[1]) * METERS_PER_DEGREE
    const px = Number(target[0]) * longitudeScale, py = Number(target[1]) * METERS_PER_DEGREE
    const dx = bx - ax, dy = by - ay
    const ratio = Math.max(0, Math.min(1, dx * dx + dy * dy <= 1e-9 ? 0 : ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    const coordinate = [0, 1, 2].map(axis => Number(from[axis] || 0) + (Number(to[axis] || 0) - Number(from[axis] || 0)) * ratio)
    const offsetMeters = horizontalDistanceMeters(coordinate, target)
    const segmentMeters = distanceMeters(from, to)
    const candidate = { coordinate, segment: index, distanceAlongMeters: distanceAlong + segmentMeters * ratio, offsetMeters }
    if (!best || candidate.offsetMeters < best.offsetMeters) best = candidate
    distanceAlong += segmentMeters
  }
  return best
}

/**
 * Returns only the still-untravelled road geometry between a vehicle and its
 * active temporary target. The target is projected onto the authoritative
 * routing polyline so this never invents a straight cross-map shortcut.
 */
export function remainingRouteToTarget(source = [], startDistanceMeters = 0, target = null, currentPosition = null) {
  const sampler = createPolylineSampler(source)
  if (sampler.points.length < 2 || sampler.total <= 0) return []
  const projection = projectOntoPolyline(sampler.points, target)
  if (!projection) return []
  const startDistance = Math.max(0, Math.min(sampler.total, Number(startDistanceMeters) || 0))
  if (projection.distanceAlongMeters <= startDistance + .05) return []
  const start = sampler.locate(startDistance / sampler.total)
  const suppliedCurrent = Array.isArray(currentPosition) && currentPosition.length >= 2
    && currentPosition.every(value => Number.isFinite(Number(value)))
    ? currentPosition.map(Number)
    : null
  const result = [(suppliedCurrent || start.coordinate).slice()]
  for (let index = start.segment + 1; index <= projection.segment; index += 1) {
    if (distanceMeters(result.at(-1), sampler.points[index]) > .02) result.push(sampler.points[index].slice())
  }
  if (distanceMeters(result.at(-1), projection.coordinate) > .02) result.push(projection.coordinate.slice())
  return result.length > 1 ? result : []
}

export function executableAirRouteFraction(sortieFraction, stagedTakeoffAndLanding = false) {
  const progress = Math.max(0, Math.min(1, Number(sortieFraction) || 0))
  if (!stagedTakeoffAndLanding) return progress
  if (progress <= .05) return 0
  if (progress >= .95) return 1
  return (progress - .05) / .9
}

export function polylineGeometryKey(source = []) {
  return (Array.isArray(source) ? source : []).map(point => [0, 1, 2]
    .map(axis => {
      const value = Number(point?.[axis] ?? 0)
      return Number.isFinite(value) ? value.toFixed(7) : 'invalid'
    })
    .join(','))
    .join('|')
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

export function selectMissionActualPoints(route = {}, mission = {}, replayPercent = 100, forceReplay = false, replayTimeMs = null) {
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
  if (!forceReplay && !['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(state)) return points
  const percent = Math.max(1, Math.min(100, Number(replayPercent) || 100))
  const timedPoints = points.filter(point => Number.isFinite(Number(point?.simulationTimeMs)))
  if (Number.isFinite(Number(replayTimeMs)) && timedPoints.length) {
    const visible = points.filter(point => Number(point?.simulationTimeMs) <= Number(replayTimeMs) + .0001)
    return visible.length ? visible : points.slice(0, 1)
  }
  const pointsWithProgress = points.filter(point => Number.isFinite(Number(point?.metrics?.routeProgress)))
  if (pointsWithProgress.length) {
    const visible = points.filter(point => Number(point?.metrics?.routeProgress) <= percent + .0001)
    return visible.length ? visible : points.slice(0, 1)
  }
  return points.slice(0, Math.max(1, Math.ceil(points.length * percent / 100)))
}

export function isTerminalMission(mission = {}) {
  return ['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(String(mission?.state || mission?.status || '').toUpperCase())
}

export function missionViewState(mission = {}, devices = [], replayPercent = 100, { forceReplay = false, replayTimeMs = null } = {}) {
  const replay = hasMissionSimulation(mission) && (forceReplay || isTerminalMission(mission))
  const metricsById = new Map()
  const routes = Array.isArray(mission?.routes) ? mission.routes : []
  routes.forEach(route => {
    const visible = selectMissionActualPoints(route, mission, replayPercent, forceReplay, replayTimeMs)
    const metrics = visible.at(-1)?.metrics
    if (metrics) metricsById.set(route.deviceId, metrics)
  })
  ;(Array.isArray(devices) ? devices : []).forEach(device => {
    if (!metricsById.has(device.deviceId) && (!replay || Number(replayPercent) >= 100)) {
      metricsById.set(device.deviceId, device.sensorData || {})
    }
  })
  const progress = replay ? Math.max(1, Math.min(100, Number(replayPercent) || 100)) : Number(mission?.progress || 0)
  const phase = routes
    .map(route => String(metricsById.get(route.deviceId)?.missionPhase || '').toUpperCase())
    .find(Boolean) || String(mission?.missionPhase || 'DOCKED').toUpperCase()
  const droneMetrics = (Array.isArray(devices) ? devices : [])
    .filter(device => device.deviceType === 'smart_drone')
    .map(device => metricsById.get(device.deviceId))
    .filter(Boolean)
  const average = (field, fallback) => droneMetrics.length
    ? droneMetrics.reduce((sum, item) => sum + Number(item?.[field] ?? fallback), 0) / droneMetrics.length
    : fallback
  const deviationValues = Array.from(metricsById.values()).map(item => Number(item?.routeDeviationMeters)).filter(Number.isFinite)
  return {
    replay,
    progress,
    phase,
    metricsById,
    summary: {
      coverage: average('deliveryProgressPercent', 0),
      battery: average('battery', 100),
      link: average('linkQuality', 100),
      deviation: deviationValues.length ? Math.max(...deviationValues) : 0
    }
  }
}

export function missionEventsAtProgress(events = [], progress = 0) {
  const current = Number(progress) || 0
  const normalized = (Array.isArray(events) ? events : []).map(event => ({
    ...event,
    reached: current >= Number(event.progress || 0)
  }))
  const reached = normalized.filter(event => event.reached).sort((a, b) => Number(b.progress) - Number(a.progress))
  const upcoming = normalized.filter(event => !event.reached).sort((a, b) => Number(a.progress) - Number(b.progress))
  return [...reached, ...upcoming]
}

export function missionModelPhaseState(type, phase) {
  const normalizedType = String(type || '').toLowerCase()
  const normalizedPhase = String(phase || '').toUpperCase()
  const isDrone = normalizedType === 'smart_drone'
  const airborne = isDrone && ['TAKEOFF', 'DELIVERING', 'RETURNING'].includes(normalizedPhase)
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
  const airborne = isDrone && ['TAKEOFF', 'DELIVERING', 'RETURNING'].includes(phase)
  const takeoffProgress = Math.max(0, Math.min(1, Number(options.takeoffProgress) || 0))
  const takeoffEase = takeoffProgress * takeoffProgress * (3 - 2 * takeoffProgress)
  const configuredPhysicalSize = Number(options.physicalSize)
  const physicalSize = configuredPhysicalSize > 0 ? configuredPhysicalSize : isDrone ? 2.6 : 6.7
  const droneTargetPixels = phase === 'TAKEOFF' ? 28 + 18 * takeoffEase : airborne ? 46 : 28
  const targetPixels = isDrone ? droneTargetPixels : 46
  const defaultMinimumScale = isDrone
    ? phase === 'TAKEOFF' ? 1.08 + .82 * takeoffEase : airborne ? 1.9 : 1.08
    : 1.72
  const configuredMinimumScale = Number(options.minimumScale)
  const minimumScale = configuredMinimumScale > 0 ? configuredMinimumScale : defaultMinimumScale
  const maxScale = isDrone ? 210 : 145
  const safeDistance = Math.max(1, Number(distance) || 1)
  const safeHeight = Math.max(1, Number(viewportHeight) || 1)
  const safeFov = Math.max(10, Math.min(120, Number(fovDegrees) || 50))
  const worldUnitsPerPixel = 2 * safeDistance * Math.tan(safeFov * Math.PI / 360) / safeHeight
  const screenScale = targetPixels * worldUnitsPerPixel / physicalSize
  const selectedFactor = options.selected ? 1.12 : 1
  return Math.min(maxScale, Math.max(minimumScale, screenScale) * selectedFactor)
}

export function missionFollowCamera(type, heading, mode = 'rear') {
  const isDrone = String(type || '').toLowerCase() === 'smart_drone'
  const normalizedMode = mode === 'side' ? 'side' : 'rear'
  // Flat MapV rotates the camera around Z, so its heading sign is opposite
  // to the map bearing used by telemetry and by the model yaw below.
  const headingOffset = normalizedMode === 'side' ? -55 : 0
  return {
    heading: (-Number(heading || 0) + headingOffset + 360) % 360,
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

export function modelYawRadians(forwardAxis, headingDegrees) {
  const northAlignment = ({ '+Y': 0, '+X': Math.PI / 2, '-Y': Math.PI, '-X': -Math.PI / 2 })[forwardAxis]
  if (northAlignment === undefined) throw new Error(`Unsupported model forward axis: ${forwardAxis}`)
  return northAlignment - Number(headingDegrees || 0) * Math.PI / 180
}

export function telemetryTransitionDuration(previousTargetAt, nextTargetAt, fallbackMs = 1000) {
  const interval = Number(nextTargetAt) - Number(previousTargetAt)
  const cadence = Number.isFinite(interval) && interval > 0 ? interval : Number(fallbackMs)
  return Math.max(850, Math.min(1600, cadence * 1.15))
}

export function telemetryMotionRatio(elapsedMs, durationMs) {
  const elapsed = Math.max(0, Number(elapsedMs) || 0)
  const duration = Math.max(1, Number(durationMs) || 1)
  return Math.min(1, elapsed / duration)
}
