import assert from 'node:assert/strict'
import test from 'node:test'
import { createMissionContext } from '../src/mission/context/createMissionContext.js'

const snapshot = {
  session: { id: 'DEMO-CTX', status: 'RUNNING' },
  mission: {
    progress: 55,
    missionPhase: 'DELIVERING',
    pairs: [{ vehicleId: 'V1', droneId: 'D1', label: '编组' }],
    events: [{ progress: 55, type: 'ROAD_OBSTACLE', label: '异常', deviceId: 'D1' }],
    routes: []
  },
  devices: [
    { deviceId: 'V1', deviceType: 'ground_vehicle', sensorData: { missionPhase: 'DELIVERING' } },
    { deviceId: 'D1', deviceType: 'smart_drone', sensorData: { missionPhase: 'DELIVERING' } }
  ]
}

test('keeps actor focus in one shared context', () => {
  const context = createMissionContext(snapshot)
  assert.equal(context.focusActor('D1'), true)
  assert.equal(context.focusedActorId.value, 'D1')
  assert.equal(context.focusedActor.value.id, 'D1')
  assert.equal(context.viewMode.value, 'ACTOR_FOCUS')
  assert.equal(context.focusActor('missing'), false)
})

test('signal focus coordinates actor and action context', () => {
  const context = createMissionContext(snapshot)
  const signalId = context.mission.value.signals[0].id
  assert.equal(context.focusSignal(signalId), true)
  assert.equal(context.focusedActorId.value, 'D1')
  assert.equal(context.focusedSignalId.value, signalId)
  assert.equal(context.actionMode.value, 'ATTENTION')
})

test('mission signals no longer request interface attention', () => {
  const value = {
    ...snapshot,
    signals: [{
      id: 'S-DEVICE-ANOMALY', key: 'device-anomaly', type: 'DEVICE_ANOMALY', label: '发现设备运行异常', severity: 'WARNING',
      status: 'DETECTED', actorIds: ['D1'], progress: 55, requiresAction: true, allowedActions: ['ACKNOWLEDGE']
    }]
  }
  const context = createMissionContext(value)
  assert.equal(context.mission.value.signals.length, 1)
  assert.equal('attentionSignal' in context, false)
})

test('uses one cursor for live and replay modes', () => {
  const replaySnapshot = {
    ...snapshot,
    session: { ...snapshot.session, status: 'COMPLETED', progress: 100 },
    mission: {
      ...snapshot.mission,
      state: 'COMPLETED',
      status: 'COMPLETED',
      simulationId: 'DEMO-CTX',
      progress: 100,
      missionPhase: 'DOCKED',
      routes: [{
        deviceId: 'D1',
        kind: 'GROUND',
        actualPoints: [
          { metrics: { routeProgress: 10, missionPhase: 'DEPART' } },
          { metrics: { routeProgress: 30, missionPhase: 'DELIVERING' } },
          { metrics: { routeProgress: 100, missionPhase: 'DOCKED' } }
        ]
      }]
    }
  }
  const context = createMissionContext(replaySnapshot)
  assert.equal(context.timeCursor.value, 100)
  context.enterReplay(30)
  assert.equal(context.timeMode.value, 'REPLAY')
  assert.equal(context.timeCursor.value, 30)
  assert.equal(context.mission.value.progress, 30)
  assert.equal(context.mission.value.activePhaseId, 'DELIVERY')
  assert.equal(context.mission.value.signals[0].status, 'SCHEDULED')
  context.ingestSnapshot({ ...replaySnapshot, mission: { ...replaySnapshot.mission, progress: 100 } })
  assert.equal(context.timeCursor.value, 30)
  context.returnToLive()
  assert.equal(context.timeCursor.value, 100)
})

test('restores server-managed signal status from mission progress history', () => {
  const replaySnapshot = {
    ...snapshot,
    session: { ...snapshot.session, status: 'COMPLETED', progress: 100, definitionVersion: '1.5.0' },
    mission: { ...snapshot.mission, simulationId: 'DEMO-CTX', status: 'COMPLETED', state: 'COMPLETED', progress: 100, missionPhase: 'DOCKED', routes: [] },
    signals: [{
      id: 'S-HISTORY', key: 'road-obstacle', type: 'ROAD_OBSTACLE', label: '异常', severity: 'WARNING',
      status: 'RESOLVED', actorIds: ['D1'], progress: 55, requiresAction: false, allowedActions: [],
      statusHistory: [
        { status: 'DETECTED', progress: 55 },
        { status: 'ACKNOWLEDGED', progress: 60 },
        { status: 'INVESTIGATING', progress: 65 },
        { status: 'RESOLVED', progress: 70 }
      ]
    }]
  }
  const context = createMissionContext(replaySnapshot)
  context.enterReplay(62)
  assert.equal(context.mission.value.signals[0].status, 'ACKNOWLEDGED')
  context.setTimeCursor(68)
  assert.equal(context.mission.value.signals[0].status, 'INVESTIGATING')
  context.setTimeCursor(75)
  assert.equal(context.mission.value.signals[0].status, 'RESOLVED')
})
