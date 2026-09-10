const EARTH_METERS_PER_LATITUDE_DEGREE = 110540

function finitePoints(points = []) {
  return points.filter(point => Array.isArray(point)
    && Number.isFinite(Number(point[0]))
    && Number.isFinite(Number(point[1])))
}

export function missionBoundsMeters(points = []) {
  const values = finitePoints(points)
  if (!values.length) return { width: 0, height: 0, extent: 0, center: [0, 0, 0] }
  const longitudes = values.map(point => Number(point[0]))
  const latitudes = values.map(point => Number(point[1]))
  const minLongitude = Math.min(...longitudes), maxLongitude = Math.max(...longitudes)
  const minLatitude = Math.min(...latitudes), maxLatitude = Math.max(...latitudes)
  const centerLatitude = (minLatitude + maxLatitude) / 2
  const longitudeMeters = 111320 * Math.max(.01, Math.cos(centerLatitude * Math.PI / 180))
  const width = (maxLongitude - minLongitude) * longitudeMeters
  const height = (maxLatitude - minLatitude) * EARTH_METERS_PER_LATITUDE_DEGREE
  return {
    width,
    height,
    extent: Math.max(width, height),
    center: [(minLongitude + maxLongitude) / 2, centerLatitude, 0],
    minLongitude,
    longitudeMeters
  }
}

export function fittedMissionRange(points = [], { factor = 1.35, minimum = 500, maximum = 12500 } = {}) {
  const { extent } = missionBoundsMeters(points)
  if (!extent) return maximum
  return Math.max(minimum, Math.min(maximum, extent * factor))
}

export function missionOverviewCamera(points = [], {
  heading = 12,
  pitch = 70,
  factor = 1.35,
  minimum = 500,
  maximum = 12500
} = {}) {
  const bounds = missionBoundsMeters(points)
  if (!bounds.extent) return null
  return {
    center: bounds.center,
    heading: Number(heading),
    pitch: Number(pitch),
    range: fittedMissionRange(points, { factor, minimum, maximum })
  }
}

export function missionViewportOptions({ range = 120, zoom = 0 } = {}) {
  const rangeValue = Number(range), zoomValue = Number(zoom)
  return {
    range: Number.isFinite(rangeValue) ? Math.max(0, Math.min(600, rangeValue)) : 120,
    zoom: Number.isFinite(zoomValue) ? Math.max(-2, Math.min(2, zoomValue)) : 0
  }
}

export function plannerAwareViewportPoints(points = [], { panelWidth = 0, viewportWidth = 0 } = {}) {
  const values = finitePoints(points)
  if (values.length < 2 || panelWidth <= 0 || viewportWidth <= 0) return values
  const bounds = missionBoundsMeters(values)
  if (!bounds.extent) return values
  const panelRatio = Math.max(.25, Math.min(.55, panelWidth / viewportWidth * 1.35))
  const westPaddingDegrees = bounds.extent * panelRatio / bounds.longitudeMeters
  return [...values, [bounds.minLongitude - westPaddingDegrees, bounds.center[1], 0]]
}
