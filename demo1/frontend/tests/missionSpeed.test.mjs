import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MISSION_TIME_SCALE_OPTIONS,
  formatMissionSpeed,
  isMissionReplayState,
  normalizeMissionTimeScale
} from '../src/utils/missionSpeed.mjs'

test('logistics speed controls expose only the supported safe presets', () => {
  assert.deepEqual(MISSION_TIME_SCALE_OPTIONS, [0.5, 1, 2, 5])
  assert.equal(normalizeMissionTimeScale('0.5'), 0.5)
  assert.equal(normalizeMissionTimeScale(5), 5)
  assert.equal(normalizeMissionTimeScale(30), 1)
})

test('telemetry speed formatting remains distinct from simulation multipliers', () => {
  assert.equal(formatMissionSpeed(27.04), '27.0 km/h')
  assert.equal(formatMissionSpeed(null), '—')
  assert.equal(formatMissionSpeed(undefined), '—')
})

test('replay controls are shown only for terminal mission states', () => {
  assert.equal(isMissionReplayState('stopped'), true)
  assert.equal(isMissionReplayState('FAILED'), true)
  assert.equal(isMissionReplayState('RUNNING'), false)
})
