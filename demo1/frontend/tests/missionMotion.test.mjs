import assert from 'node:assert/strict'
import test from 'node:test'
import {
  adjustFollowZoomScale,
  bearingDegrees,
  createPolylineSampler,
  executableAirRouteFraction,
  densifyCatmullRom,
  hasMissionSimulation,
  interpolateHeading,
  missionEventsAtProgress,
  missionViewState,
  modelYawRadians,
  modelVisibilityScale,
  movementHeadingDegrees,
  missionFollowCamera,
  missionModelPhaseState,
  polylineGeometryKey,
  selectMissionActualPoints,
  telemetryMotionRatio,
  telemetryTransitionDuration
} from '../src/utils/missionMotion.mjs'

test('dynamic air sortie progress maps take-off and landing margins onto the executable route', () => {
  assert.equal(executableAirRouteFraction(0, true), 0)
  assert.equal(executableAirRouteFraction(.05, true), 0)
  assert.equal(executableAirRouteFraction(.5, true), .5)
  assert.equal(executableAirRouteFraction(.95, true), 1)
  assert.equal(executableAirRouteFraction(.6, false), .6)
})

test('north/east bearings use map heading convention', () => {
  assert.ok(Math.abs(bearingDegrees([117, 31, 0], [117, 31.01, 0])) < 0.001)
  assert.ok(Math.abs(bearingDegrees([117, 31, 0], [117.01, 31, 0]) - 90) < 0.001)
})

test('movement heading follows actual displacement in every compass direction', () => {
  const origin = [117, 31, 20]
  assert.equal(Math.round(movementHeadingDegrees(origin, [117, 31.001, 20], 123)), 0)
  assert.equal(Math.round(movementHeadingDegrees(origin, [117.001, 31, 20], 123)), 90)
  assert.equal(Math.round(movementHeadingDegrees(origin, [117, 30.999, 20], 123)), 180)
  assert.equal(Math.round(movementHeadingDegrees(origin, [116.999, 31, 20], 123)), 270)
  assert.equal(movementHeadingDegrees(origin, [117, 31, 80], 123), 123)
})

test('polyline heading uses the current segment instead of cutting across route corners', () => {
  const sampler = createPolylineSampler([
    [117, 31, 0],
    [117, 31.01, 0],
    [117.01, 31.01, 0],
    [117.01, 31.02, 0]
  ])
  assert.equal(Math.round(sampler.locate(.16).heading), 0)
  assert.equal(Math.round(sampler.locate(.50).heading), 90)
  assert.equal(Math.round(sampler.locate(.84).heading), 0)
})

test('polyline heading skips duplicate waypoints without losing route direction', () => {
  const sampler = createPolylineSampler([[117, 31, 0], [117, 31, 0], [117.01, 31, 0]])
  assert.equal(Math.round(sampler.locate(0).heading), 90)
  assert.equal(Math.round(sampler.locate(.5).heading), 90)
})

test('arc-length sampler remains on the supplied road polyline', () => {
  const sampler = createPolylineSampler([[117, 31, 0], [117.001, 31, 0], [117.001, 31.001, 0]])
  for (let progress = 0; progress <= 1; progress += 0.01) {
    const { coordinate } = sampler.locate(progress)
    const onHorizontal = Math.abs(coordinate[1] - 31) < 1e-10
    const onVertical = Math.abs(coordinate[0] - 117.001) < 1e-10
    assert.ok(onHorizontal || onVertical)
  }
})

test('route geometry cache key changes when only interior detour points change', () => {
  const original = [[117, 31, 70], [117.001, 31, 70], [117.002, 31, 70]]
  const detour = [[117, 31, 70], [117.001, 31.002, 70], [117.002, 31, 70]]
  assert.equal(polylineGeometryKey(original), polylineGeometryKey(original.map(point => [...point])))
  assert.notEqual(polylineGeometryKey(original), polylineGeometryKey(detour))
})

test('heading interpolation takes the shortest turn through north', () => {
  assert.equal(Math.round(interpolateHeading(350, 10, 0.5)), 0)
})

test('model yaw aligns every supported authored forward axis with map heading', () => {
  assert.equal(modelYawRadians('+Y', 0), 0)
  assert.equal(modelYawRadians('+X', 0), Math.PI / 2)
  assert.equal(modelYawRadians('-Y', 0), Math.PI)
  assert.equal(modelYawRadians('-X', 0), -Math.PI / 2)
  assert.equal(modelYawRadians('-Y', 90), Math.PI / 2)
  assert.throws(() => modelYawRadians('+Z', 0), /Unsupported model forward axis/)
})

test('live telemetry motion stays linear and overlaps the next one-second update', () => {
  assert.equal(telemetryMotionRatio(250, 1000), .25)
  assert.equal(telemetryMotionRatio(1000, 1000), 1)
  assert.equal(telemetryTransitionDuration(1000, 2000), 1150)
  assert.equal(telemetryTransitionDuration(0, 3000), 1600)
})

test('one-second telemetry updates keep advancing without an idle frame', () => {
  let from = 0
  let to = 1
  let startedAt = 0
  let duration = 1150
  let current = 0
  const positions = []
  for (let now = 100; now <= 4000; now += 100) {
    current = from + (to - from) * telemetryMotionRatio(now - startedAt, duration)
    if (now % 1000 === 0) {
      from = current
      to += 1
      duration = telemetryTransitionDuration(startedAt, now)
      startedAt = now
    }
    positions.push(current)
  }
  assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]))
})

test('air curve keeps exact launch and landing endpoints', () => {
  const source = [[117, 31, 5], [117.01, 31.01, 80], [117.02, 31, 5]]
  const curve = densifyCatmullRom(source)
  assert.deepEqual(curve[0], source[0])
  assert.deepEqual(curve.at(-1), source.at(-1))
  assert.ok(curve.length > source.length)
})

test('standby mission never exposes historical actual points', () => {
  const route = { kind: 'AIR', actualPoints: [{ longitude: 117, latitude: 31 }] }
  const mission = { state: 'STANDBY', simulationId: null }
  assert.equal(hasMissionSimulation(mission), false)
  assert.deepEqual(selectMissionActualPoints(route, mission), [])
})

test('running ground track keeps the ordered telemetry points unchanged', () => {
  const points = [
    { longitude: 117, latitude: 31, metrics: { missionPhase: 'DEPART' } },
    { longitude: 117.001, latitude: 31.001, metrics: { missionPhase: 'DEPART' } }
  ]
  const route = { kind: 'GROUND', actualPoints: points }
  const mission = { state: 'RUNNING', simulationId: 'SIM-1' }
  assert.equal(hasMissionSimulation(mission), true)
  assert.deepEqual(selectMissionActualPoints(route, mission), points)
})

test('air track begins at takeoff instead of drawing the carried segment', () => {
  const takeoff = { longitude: 117.001, latitude: 31.001, metrics: { missionPhase: 'TAKEOFF' } }
  const scanning = { longitude: 117.002, latitude: 31.002, metrics: { missionPhase: 'DELIVERING' } }
  const route = {
    kind: 'AIR',
    actualPoints: [
      { longitude: 117, latitude: 31, metrics: { missionPhase: 'DEPART', trackSegment: 'CARRY_TO_LAUNCH' } },
      takeoff,
      scanning
    ]
  }
  const mission = { state: 'RUNNING', simulationId: 'SIM-1' }
  assert.deepEqual(selectMissionActualPoints(route, mission), [takeoff, scanning])
})

test('every terminal mission supports replay slicing', () => {
  const points = Array.from({ length: 10 }, (_, index) => ({ longitude: 117 + index / 1000, latitude: 31 }))
  for (const state of ['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED']) {
    assert.equal(selectMissionActualPoints({ kind: 'GROUND', actualPoints: points }, { state, simulationId: 'SIM-1' }, 30).length, 3)
  }
})

test('mission view uses the same replay instant for progress, phase, and metrics', () => {
  const route = {
    deviceId: 'HF-UAV-000003',
    kind: 'GROUND',
    actualPoints: [
      { metrics: { routeProgress: 10, missionPhase: 'DEPART', deliveryProgressPercent: 5, battery: 96, linkQuality: 99, routeDeviationMeters: 1 } },
      { metrics: { routeProgress: 60, missionPhase: 'DELIVERING', deliveryProgressPercent: 55, battery: 84, linkQuality: 90, routeDeviationMeters: 4 } },
      { metrics: { routeProgress: 100, missionPhase: 'DOCKED', deliveryProgressPercent: 100, battery: 76, linkQuality: 88, routeDeviationMeters: 2 } }
    ]
  }
  const device = { deviceId: route.deviceId, deviceType: 'smart_drone', sensorData: route.actualPoints.at(-1).metrics }
  const view = missionViewState({ state: 'COMPLETED', simulationId: 'SIM-1', progress: 100, missionPhase: 'DOCKED', routes: [route] }, [device], 34)
  assert.equal(view.progress, 34)
  assert.equal(view.phase, 'DEPART')
  assert.equal(view.summary.coverage, 5)
  assert.equal(view.summary.deviation, 1)
})

test('terminal replay follows recorded mission progress instead of sample count when metrics exist', () => {
  const points = [5, 12, 28, 45, 80, 100].map(routeProgress => ({ metrics: { routeProgress } }))
  const visible = selectMissionActualPoints({ kind: 'GROUND', actualPoints: points }, { state: 'COMPLETED', simulationId: 'SIM-1' }, 30)
  assert.deepEqual(visible.map(point => point.metrics.routeProgress), [5, 12, 28])
})

test('events show recent occurred items before nearest upcoming items', () => {
  const events = [{ progress: 10 }, { progress: 55 }, { progress: 75 }, { progress: 100 }]
  const result = missionEventsAtProgress(events, 60)
  assert.deepEqual(result.map(item => item.progress), [55, 10, 75, 100])
  assert.deepEqual(result.map(item => item.reached), [true, true, false, false])
})

test('drone model effects follow the mission phase', () => {
  assert.deepEqual(missionModelPhaseState('smart_drone', 'DEPART'), {
    airborne: false,
    docked: true,
    rotorActive: false,
    showAltitude: false,
    showGroundRing: false,
    showHalo: false
  })
  assert.equal(missionModelPhaseState('smart_drone', 'DELIVERING').airborne, true)
  assert.equal(missionModelPhaseState('smart_drone', 'RETURNING').showAltitude, true)
  assert.equal(missionModelPhaseState('ground_vehicle', 'DELIVERING').showGroundRing, true)
})

test('model visibility scale enlarges the vehicle and lets the drone grow during takeoff', () => {
  assert.equal(modelVisibilityScale('ground_vehicle', 100, 50, 800), 1.72)
  assert.ok(modelVisibilityScale('ground_vehicle', 12000, 50, 700) > 100)
  const docked = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'DOCKED' })
  const takeoffStart = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'TAKEOFF', takeoffProgress: 0 })
  const takeoffEnd = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'TAKEOFF', takeoffProgress: 1 })
  assert.equal(docked, 1.08)
  assert.equal(takeoffStart, docked)
  assert.ok(takeoffEnd > takeoffStart)
  assert.ok(modelVisibilityScale('smart_drone', 12000, 50, 700, { phase: 'DELIVERING' }) > modelVisibilityScale('ground_vehicle', 12000, 50, 700))
  assert.ok(modelVisibilityScale('smart_drone', 240, 50, 700, { phase: 'DELIVERING', selected: true }) > modelVisibilityScale('smart_drone', 240, 50, 700, { phase: 'DELIVERING' }))
})

test('follow camera supports rear and side tracking for both vehicle types', () => {
  assert.deepEqual(missionFollowCamera('ground_vehicle', 40, 'rear'), { heading: 320, pitch: 59, range: 72 })
  assert.deepEqual(missionFollowCamera('ground_vehicle', 40, 'side'), { heading: 265, pitch: 54, range: 86 })
  assert.deepEqual(missionFollowCamera('smart_drone', 350, 'rear'), { heading: 10, pitch: 59, range: 96 })
  assert.deepEqual(missionFollowCamera('smart_drone', 30, 'side'), { heading: 275, pitch: 54, range: 112 })
})

test('follow zoom scale responds to the wheel and remains within safe camera bounds', () => {
  assert.ok(adjustFollowZoomScale(1, -120) < 1)
  assert.ok(adjustFollowZoomScale(1, 120) > 1)
  assert.equal(adjustFollowZoomScale(.65, -480), .65)
  assert.equal(adjustFollowZoomScale(4, 480), 4)
})
