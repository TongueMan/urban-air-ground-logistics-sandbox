import assert from 'node:assert/strict'
import test from 'node:test'
import { initializeApplication } from '../src/runtime/initializeApplication.mjs'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

test('establishes the fleet visitor identity before loading the mission runtime', async () => {
  const calls = []
  let releaseFleet
  const fleetGate = new Promise(resolve => { releaseFleet = resolve })
  const fleetRuntime = {
    initialize: async () => {
      calls.push('fleet:start')
      await fleetGate
      calls.push('fleet:done')
      return 'fleet'
    }
  }
  const runtime = {
    initialize: async () => {
      calls.push('mission:start')
      return 'mission'
    }
  }

  const initialization = initializeApplication(runtime, fleetRuntime)
  await Promise.resolve()
  assert.deepEqual(calls, ['fleet:start'])

  releaseFleet()
  const result = await initialization

  assert.deepEqual(calls, ['fleet:start', 'fleet:done', 'mission:start'])
  assert.equal(result.fleet.status, 'fulfilled')
  assert.equal(result.mission.status, 'fulfilled')
})

test('still loads the mission runtime when fleet initialization fails', async () => {
  const calls = []
  const expected = new Error('fleet unavailable')
  const result = await initializeApplication(
    { initialize: async () => { calls.push('mission'); return 'mission' } },
    { initialize: async () => { calls.push('fleet'); throw expected } }
  )

  assert.deepEqual(calls, ['fleet', 'mission'])
  assert.equal(result.fleet.status, 'rejected')
  assert.equal(result.fleet.reason, expected)
  assert.equal(result.mission.status, 'fulfilled')
})

test('mission initialization does not refetch the complete current snapshot', () => {
  const runtime = readFileSync(join(frontendRoot, 'src', 'mission', 'runtime', 'useLogisticsMissionRuntime.js'), 'utf8')
  assert.doesNotMatch(runtime, /if \(session\.value\?\.id\) \{\s*await refreshMission\(\)/)
})
