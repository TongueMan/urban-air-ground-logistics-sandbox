import assert from 'node:assert/strict'
import test from 'node:test'
import { adaptCurrentDemoSnapshot } from '../src/mission/adapters/logisticsMissionAdapter.mjs'

function snapshot(overrides = {}) {
  return {
    session: { id: 'DEMO-1', status: 'RUNNING', progress: 55 },
    mission: {
      scenarioKey: 'urban-logistics-operation',
      version: '1.4.0',
      name: '测试任务',
      progress: 55,
      missionPhase: 'DELIVERING',
      phaseSchedule: { DEPART: [0, 10], TAKEOFF: [10, 20], DELIVERING: [20, 75], RETURNING: [75, 90], DOCKED: [90, 100] },
      pairs: [{ vehicleId: 'vehicle-alpha', droneId: 'aircraft-bravo', slot: 2, label: '测试编组' }],
      events: [{ progress: 55, type: 'ROAD_OBSTACLE', label: '发现障碍', deviceId: 'aircraft-bravo', confidence: .91 }],
      routes: []
    },
    devices: [
      { deviceId: 'vehicle-alpha', deviceType: 'ground_vehicle', deviceName: '移动基站', longitude: 117, latitude: 31, sensorData: { missionPhase: 'DELIVERING', battery: 92 } },
      { deviceId: 'aircraft-bravo', deviceType: 'smart_drone', deviceName: '配送单元', longitude: 117.1, latitude: 31.1, altitude: 70, sensorData: { missionPhase: 'DELIVERING', battery: 82, linkQuality: 96, deliveryProgressPercent: 54 } }
    ],
    ...overrides
  }
}

test('normalizes phases once and exposes progress as presentation data', () => {
  const mission = adaptCurrentDemoSnapshot(snapshot())
  assert.equal(mission.activePhaseId, 'DELIVERY')
  assert.equal(mission.phases.length, 5)
  assert.equal(mission.phases.find(phase => phase.id === 'DELIVERY').state, 'ACTIVE')
  assert.equal(mission.progress, 55)
})

test('standby missions do not pretend recovery is an active phase', () => {
  const value = snapshot()
  value.session = null
  value.mission.progress = 0
  value.mission.missionPhase = 'DOCKED'
  value.mission.state = 'STANDBY'
  value.mission.status = 'STANDBY'
  const mission = adaptCurrentDemoSnapshot(value)
  assert.equal(mission.activePhaseId, null)
  assert.ok(mission.phases.every(phase => phase.state === 'PENDING'))
})

test('uses schedule progress during the first running tick instead of stale docked state', () => {
  const value = snapshot()
  value.session.progress = 0
  value.session.missionPhase = 'DOCKED'
  value.mission.progress = 0
  value.mission.missionPhase = 'DOCKED'
  const mission = adaptCurrentDemoSnapshot(value)
  assert.equal(mission.activePhaseId, 'DEPLOY')
  assert.equal(mission.phases[0].state, 'ACTIVE')
})

test('uses explicit deviceType rather than actor id naming conventions', () => {
  const mission = adaptCurrentDemoSnapshot(snapshot())
  assert.equal(mission.actorsById['vehicle-alpha'].kind, 'VEHICLE')
  assert.equal(mission.actorsById['aircraft-bravo'].kind, 'UAV')
  assert.equal(mission.actorsById['aircraft-bravo'].name, '配送单元')
})

test('aggregates more than one drone into a vehicle formation', () => {
  const value = snapshot()
  value.mission.pairs.push({ vehicleId: 'vehicle-alpha', droneId: 'aircraft-charlie', slot: 3, label: '测试编组' })
  value.devices.push({ deviceId: 'aircraft-charlie', deviceType: 'smart_drone', deviceName: '配送单元 C', longitude: 117.2, latitude: 31.2, sensorData: {} })
  const mission = adaptCurrentDemoSnapshot(value)
  assert.equal(mission.formations.length, 1)
  assert.deepEqual(mission.formations[0].members.map(member => member.actorId), ['vehicle-alpha', 'aircraft-bravo', 'aircraft-charlie'])
})

test('normalizes current demo events into stable signals without fake command actions', () => {
  const mission = adaptCurrentDemoSnapshot(snapshot())
  const signal = mission.signals[0]
  assert.equal(signal.status, 'DETECTED')
  assert.equal(signal.severity, 'WARNING')
  assert.equal(signal.requiresAction, true)
  assert.ok(signal.actions.every(action => action.kind === 'FOCUS_ACTOR'))
})

test('prefers server-managed signal identity, lifecycle, and allowed workflow actions', () => {
  const value = snapshot()
  value.session.definitionVersion = '1.5.0'
  value.signals = [{
    id: 'SIG-DEMO-1-road-obstacle-primary',
    key: 'road-obstacle-primary',
    type: 'ROAD_OBSTACLE',
    label: '发现障碍',
    severity: 'WARNING',
    status: 'ACKNOWLEDGED',
    actorIds: ['aircraft-bravo'],
    progress: 55,
    detectedAt: '2026-09-05T00:00:00Z',
    updatedAt: '2026-09-05T00:01:00Z',
    requiresAction: true,
    allowedActions: ['BEGIN_INVESTIGATION', 'RESOLVE', 'IGNORE'],
    statusHistory: [
      { status: 'DETECTED', progress: 55, at: '2026-09-05T00:00:00Z' },
      { status: 'ACKNOWLEDGED', progress: 58, at: '2026-09-05T00:01:00Z' }
    ],
    source: 'MISSION_EVENT'
  }]
  value.mission.events[0].id = 'road-obstacle-primary'
  value.mission.events[0].requiresAction = true
  const mission = adaptCurrentDemoSnapshot(value)
  const signal = mission.signals[0]
  assert.equal(mission.definitionVersion, '1.5.0')
  assert.equal(signal.id, 'SIG-DEMO-1-road-obstacle-primary')
  assert.equal(signal.status, 'ACKNOWLEDGED')
  assert.equal(signal.serverManaged, true)
  assert.equal(signal.statusHistory[1].status, 'ACKNOWLEDGED')
  assert.deepEqual(signal.actions.filter(action => action.kind === 'WORKFLOW_COMMAND').map(action => action.commandType), ['BEGIN_INVESTIGATION', 'RESOLVE', 'IGNORE'])
})

test('prefers explicit server fleet metadata and command transport boundaries', () => {
  const value = snapshot()
  value.devices[1] = {
    ...value.devices[1],
    actorKind: 'UAV',
    actorRole: 'AIR_COURIER',
    capabilities: ['DELIVERY', 'THERMAL'],
    formationId: 'FORMATION-X',
    assignmentId: 'ASSIGN-X',
    commandCapabilities: ['RETURN', 'REDELIVER'],
    commandTransport: { status: 'UNAVAILABLE', reason: '测试边界' }
  }
  value.mission.formations = [{ id: 'FORMATION-X', label: '显式编组', leaderActorId: 'vehicle-alpha', members: [{ actorId: 'aircraft-bravo', role: 'AIR_COURIER', slot: 1 }] }]
  value.mission.assignments = [{ id: 'ASSIGN-X', actorId: 'aircraft-bravo', role: 'AIR_COURIER', routeId: 'AIR-X', status: 'ASSIGNED' }]
  const mission = adaptCurrentDemoSnapshot(value)
  const actor = mission.actorsById['aircraft-bravo']
  assert.deepEqual(actor.capabilities, ['DELIVERY', 'THERMAL'])
  assert.equal(actor.formationId, 'FORMATION-X')
  assert.equal(actor.commandTransport.status, 'UNAVAILABLE')
  assert.equal(mission.assignments[0].routeId, 'AIR-X')
})
