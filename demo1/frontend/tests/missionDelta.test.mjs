import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeDemoDelta } from '../src/mission/adapters/mergeDemoDelta.mjs'

function baseline() {
  return {
    revision: 4,
    session: { id: 'DEMO-DELTA', status: 'RUNNING', progress: 20 },
    mission: { progress: 20, missionPhase: 'DELIVERING', routes: [{ deviceId: 'D1', actualPoints: [{ eventTime: 't0' }] }] },
    devices: [{ deviceId: 'D1', sensorData: { battery: 90 } }],
    signals: [{ id: 'S1', status: 'SCHEDULED' }]
  }
}

test('merges ordered mission and signal deltas', () => {
  const mission = mergeDemoDelta(baseline(), 'mission-delta', { revision: 5, mission: { progress: 21 }, session: { progress: 21 } })
  assert.equal(mission.revision, 5)
  assert.equal(mission.mission.progress, 21)
  const signal = mergeDemoDelta(mission, 'signal-delta', { revision: 6, signal: { id: 'S1', status: 'DETECTED' } })
  assert.equal(signal.signals[0].status, 'DETECTED')
})

test('appends an exact track point without mutating the previous snapshot', () => {
  const value = baseline()
  const point = { eventTime: 't1', longitude: 117, latitude: 31 }
  const merged = mergeDemoDelta(value, 'track-delta', { revision: 5, device: { deviceId: 'D1', sensorData: { battery: 89 } }, point })
  assert.equal(value.mission.routes[0].actualPoints.length, 1)
  assert.deepEqual(merged.mission.routes[0].actualPoints[1], point)
  assert.equal(merged.devices[0].sensorData.battery, 89)
})

test('rejects revision gaps and ignores already applied events', () => {
  const value = baseline()
  assert.equal(mergeDemoDelta(value, 'mission-delta', { revision: 7, mission: { progress: 30 } }), null)
  assert.equal(mergeDemoDelta(value, 'mission-delta', { revision: 4, mission: { progress: 30 } }), value)
})

test('merges airspace lifecycle and conflict deltas', () => {
  const previous = { revision: 8, mission: { airspace: { runtimeVolumes: [] } }, devices: [] }
  const airspace = { runtimeVolumes: [{ id: 'TNFZ-001', state: 'ACTIVE', threatLevel: 'CONFLICT' }], conflicts: [{ volumeId: 'TNFZ-001' }] }
  const merged = mergeDemoDelta(previous, 'airspace-delta', { revision: 9, airspace })
  assert.equal(merged.mission.airspace.runtimeVolumes[0].state, 'ACTIVE')
  assert.equal(merged.mission.airspace.conflicts[0].volumeId, 'TNFZ-001')
})

test('merges the one-shot red airspace warning with its forced speed update', () => {
  const previous = { revision: 8, session: { id: 'RUN-1', timeScale: 5 }, mission: { airspace: {} }, devices: [] }
  const airspace = { runtimeVolumes: [{ id: 'NFZ-001', ruleType: 'ABSOLUTE_NO_FLY' }], conflicts: [{ volumeId: 'NFZ-001' }] }
  const merged = mergeDemoDelta(previous, 'airspace-warning', {
    revision: 9,
    session: { timeScale: 1 },
    airspace,
    warning: { volumeId: 'NFZ-001', level: 'WARNING' }
  })
  assert.equal(merged.session.timeScale, 1)
  assert.equal(merged.mission.airspace.runtimeVolumes[0].id, 'NFZ-001')
})

test('merges authoritative reward and fine economy deltas', () => {
  const previous = baseline()
  const economy = {
    balanceMinor: 10_050_000,
    grossRewardMinor: 230_000,
    collectedRewardMinor: 50_000,
    penaltyChargedMinor: 0,
    netMinor: 50_000,
    collectedDeliveryPointIds: ['DELIVERY-AIR-01']
  }
  const merged = mergeDemoDelta(previous, 'economy-delta', {
    revision: 5,
    economy,
    transaction: { entryType: 'DELIVERY_REWARD', amountMinor: 50_000 }
  })
  assert.deepEqual(merged.mission.economy, economy)
  assert.equal(merged.mission.progress, 20)
  assert.equal(previous.mission.economy, undefined)
})

test('merges a rewind checkpoint without disturbing live route or device state', () => {
  const previous = baseline()
  const timeline = {
    liveProgress: 20,
    liveSimulationTimeMs: 18_000,
    timelineEpoch: 0,
    latestCheckpointId: 'RWC-1',
    rewindEligible: true,
    checkpoints: [{ id: 'RWC-1', volumeId: 'TNFZ-001', progress: 18.5, status: 'AVAILABLE', available: true }]
  }
  const merged = mergeDemoDelta(previous, 'rewind-checkpoint', {
    revision: 5,
    checkpoint: timeline.checkpoints[0],
    timeline
  })

  assert.deepEqual(merged.mission.timeline, timeline)
  assert.equal(merged.mission.routes, previous.mission.routes)
  assert.equal(merged.devices, previous.devices)
})
