import assert from 'node:assert/strict'
import test from 'node:test'
import {
  adjustFollowZoomScale,
  bearingDegrees,
  createPolylineSampler,
  densifyCatmullRom,
  hasMissionSimulation,
  interpolateHeading,
  modelVisibilityScale,
  patrolFollowCamera,
  patrolModelPhaseState,
  selectMissionActualPoints
} from '../src/utils/patrolMotion.mjs'

test('north/east bearings use map heading convention', () => {
  assert.ok(Math.abs(bearingDegrees([117, 31, 0], [117, 31.01, 0])) < 0.001)
  assert.ok(Math.abs(bearingDegrees([117, 31, 0], [117.01, 31, 0]) - 90) < 0.001)
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

test('heading interpolation takes the shortest turn through north', () => {
  assert.equal(Math.round(interpolateHeading(350, 10, 0.5)), 0)
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
  const scanning = { longitude: 117.002, latitude: 31.002, metrics: { missionPhase: 'SCANNING' } }
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

test('drone model effects follow the mission phase', () => {
  assert.deepEqual(patrolModelPhaseState('smart_drone', 'DEPART'), {
    airborne: false,
    docked: true,
    rotorActive: false,
    showAltitude: false,
    showGroundRing: false,
    showHalo: false
  })
  assert.equal(patrolModelPhaseState('smart_drone', 'SCANNING').airborne, true)
  assert.equal(patrolModelPhaseState('smart_drone', 'RETURNING').showAltitude, true)
  assert.equal(patrolModelPhaseState('patrol_car', 'SCANNING').showGroundRing, true)
})

test('model visibility scale enlarges the vehicle and lets the drone grow during takeoff', () => {
  assert.equal(modelVisibilityScale('patrol_car', 100, 50, 800), 1.72)
  assert.ok(modelVisibilityScale('patrol_car', 12000, 50, 700) > 100)
  const docked = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'DOCKED' })
  const takeoffStart = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'TAKEOFF', takeoffProgress: 0 })
  const takeoffEnd = modelVisibilityScale('smart_drone', 50, 50, 800, { phase: 'TAKEOFF', takeoffProgress: 1 })
  assert.equal(docked, 1.08)
  assert.equal(takeoffStart, docked)
  assert.ok(takeoffEnd > takeoffStart)
  assert.ok(modelVisibilityScale('smart_drone', 12000, 50, 700, { phase: 'SCANNING' }) > modelVisibilityScale('patrol_car', 12000, 50, 700))
  assert.ok(modelVisibilityScale('smart_drone', 240, 50, 700, { phase: 'SCANNING', selected: true }) > modelVisibilityScale('smart_drone', 240, 50, 700, { phase: 'SCANNING' }))
})

test('follow camera supports rear and side tracking for both vehicle types', () => {
  assert.deepEqual(patrolFollowCamera('patrol_car', 40, 'rear'), { heading: 328, pitch: 59, range: 72 })
  assert.deepEqual(patrolFollowCamera('patrol_car', 40, 'side'), { heading: 40, pitch: 54, range: 86 })
  assert.deepEqual(patrolFollowCamera('smart_drone', 350, 'rear'), { heading: 278, pitch: 59, range: 96 })
  assert.deepEqual(patrolFollowCamera('smart_drone', 30, 'side'), { heading: 30, pitch: 54, range: 112 })
})

test('follow zoom scale responds to the wheel and remains within safe camera bounds', () => {
  assert.ok(adjustFollowZoomScale(1, -120) < 1)
  assert.ok(adjustFollowZoomScale(1, 120) > 1)
  assert.equal(adjustFollowZoomScale(.65, -480), .65)
  assert.equal(adjustFollowZoomScale(4, 480), 4)
})
