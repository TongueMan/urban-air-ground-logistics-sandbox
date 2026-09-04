import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PATROL_TIME_SCALE_OPTIONS,
  formatPatrolSpeed,
  isPatrolReplayState,
  normalizePatrolTimeScale
} from '../src/utils/patrolSpeed.mjs'

test('patrol speed controls expose only the supported safe presets', () => {
  assert.deepEqual(PATROL_TIME_SCALE_OPTIONS, [0.5, 1, 2, 5])
  assert.equal(normalizePatrolTimeScale('0.5'), 0.5)
  assert.equal(normalizePatrolTimeScale(5), 5)
  assert.equal(normalizePatrolTimeScale(30), 1)
})

test('telemetry speed formatting remains distinct from simulation multipliers', () => {
  assert.equal(formatPatrolSpeed(27.04), '27.0 km/h')
  assert.equal(formatPatrolSpeed(null), '—')
  assert.equal(formatPatrolSpeed(undefined), '—')
})

test('replay controls are shown only for terminal mission states', () => {
  assert.equal(isPatrolReplayState('stopped'), true)
  assert.equal(isPatrolReplayState('FAILED'), true)
  assert.equal(isPatrolReplayState('RUNNING'), false)
})
