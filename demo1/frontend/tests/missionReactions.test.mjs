import assert from 'node:assert/strict'
import { existsSync, readFileSync, readSync, openSync, closeSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import {
  MISSION_REACTION_ASSETS,
  MISSION_REACTION_ASSET_PATHS,
  MISSION_REACTION_DURATION_MS,
  MISSION_REACTION_HOLD_MS,
  MISSION_REACTION_PRESENTATION,
  MISSION_SETTLEMENT_DIALOGUE,
  buildMissionSettlement,
  createMissionReactionState,
  dismissMissionSettlement,
  enqueueAirspaceEntryReaction,
  enqueueMissionReaction,
  finishActiveMissionReaction,
  formatMissionMoney,
  isLiveMissionCompletion,
  incursionToMissionReaction,
  requestMissionSettlement,
  revealPendingMissionSettlement,
  startNextMissionReaction,
  transactionToMissionReaction
} from '../src/mission/presentation/missionReactions.mjs'

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

test('maps only diamond rewards and airspace fines to character reactions', () => {
  assert.deepEqual(transactionToMissionReaction({
    id: 'diamond-1', entryType: 'DIAMOND_REWARD', runId: 'run-1', amountMinor: 360000
  }), {
    id: 'diamond-1', kind: 'diamond', entryType: 'DIAMOND_REWARD', runId: 'run-1', amountMinor: 360000
  })
  assert.deepEqual(transactionToMissionReaction({
    entryKey: 'fine-1', entryType: 'AIRSPACE_FINE', runId: 'run-1', amountMinor: -90000, assessedAmountMinor: -132000
  }), {
    id: 'fine-1', kind: 'fine', entryType: 'AIRSPACE_FINE', runId: 'run-1', amountMinor: 90000, assessedAmountMinor: 132000
  })
  assert.equal(transactionToMissionReaction({ id: 'coin-1', entryType: 'DELIVERY_REWARD', amountMinor: 50000 }), null)
  assert.equal(MISSION_REACTION_HOLD_MS, 2500)
  assert.equal(MISSION_REACTION_DURATION_MS, 3400)
})

test('maps each live no-fly incursion to one immediate entry warning', () => {
  const incursion = {
    id: 'incursion-1', volumeId: 'NFZ-001', actorId: 'uav-1', estimatedFineMinor: 132000
  }
  assert.deepEqual(incursionToMissionReaction(incursion, 'run-1'), {
    id: 'AIRSPACE_ENTRY:incursion-1',
    kind: 'fine-entry',
    entryType: 'AIRSPACE_ENTRY',
    runId: 'run-1',
    volumeId: 'NFZ-001',
    estimatedFineMinor: 132000
  })
  assert.equal(incursionToMissionReaction({}, 'run-1'), null)

  let state = createMissionReactionState()
  state = enqueueAirspaceEntryReaction(state, incursion, 'run-1')
  state = enqueueAirspaceEntryReaction(state, incursion, 'run-1')
  assert.deepEqual(state.queue.map(item => item.id), ['AIRSPACE_ENTRY:incursion-1'])
})

test('deduplicates transactions, preserves event order and reveals settlement after reactions', () => {
  let state = createMissionReactionState()
  const diamond = { id: 'diamond-1', entryType: 'DIAMOND_REWARD', runId: 'run-1', amountMinor: 360000 }
  const fine = { id: 'fine-1', entryType: 'AIRSPACE_FINE', runId: 'run-1', amountMinor: -132000 }
  state = enqueueMissionReaction(state, diamond)
  state = enqueueMissionReaction(state, fine)
  state = enqueueMissionReaction(state, diamond)
  assert.deepEqual(state.queue.map(item => item.id), ['diamond-1', 'fine-1'])

  state = startNextMissionReaction(state)
  assert.equal(state.active.id, 'diamond-1')
  state = requestMissionSettlement(state, buildMissionSettlement('run-1', {
    collectedRewardMinor: 510000,
    groundCargoRewardMinor: 90000,
    timelinessRewardMinor: 30000,
    airCoinRewardMinor: 30000,
    collectedDiamondMinor: 360000,
    penaltyChargedMinor: 132000,
    netMinor: 378000
  }))
  assert.equal(revealPendingMissionSettlement(state), state)

  state = finishActiveMissionReaction(state)
  state = startNextMissionReaction(state)
  assert.equal(state.active.id, 'fine-1')
  state = finishActiveMissionReaction(state)
  state = revealPendingMissionSettlement(state)
  assert.equal(state.settlement.runId, 'run-1')
  assert.equal(state.pendingSettlement, null)
  assert.equal(state.settlement.netMinor, 378000)
  assert.equal(state.settlement.groundCargoRewardMinor, 90000)
  assert.equal(state.settlement.timelinessRewardMinor, 30000)

  const duplicateSettlement = requestMissionSettlement(state, buildMissionSettlement('run-1', {}))
  assert.equal(duplicateSettlement, state)
  state = dismissMissionSettlement(state)
  assert.equal(state.settlement, null)
})

test('detects only a live dynamic run transition from running to completed', () => {
  const running = { id: 'run-1', taskInstanceId: 'task-1', status: 'RUNNING' }
  const completed = { ...running, status: 'COMPLETED' }
  assert.equal(isLiveMissionCompletion(running, completed), true)
  assert.equal(isLiveMissionCompletion(running, completed, { replay: true }), false)
  assert.equal(isLiveMissionCompletion({ ...running, id: 'run-0' }, completed), false)
  assert.equal(isLiveMissionCompletion({ ...running, status: 'STOPPED' }, completed), false)
  assert.equal(isLiveMissionCompletion(running, { ...completed, taskInstanceId: '' }), false)
  assert.equal(isLiveMissionCompletion(null, completed), false)
})

test('normalizes settlement values and formats signed source amounts safely', () => {
  assert.deepEqual(buildMissionSettlement('run-2', {
    collectedRewardMinor: 500000,
    groundCargoRewardMinor: 80000,
    timelinessRewardMinor: 20000,
    airCoinRewardMinor: 40000,
    collectedDiamondMinor: 360000,
    penaltyChargedMinor: 620000,
    netMinor: -120000
  }), {
    runId: 'run-2', totalRewardMinor: 500000, groundCargoRewardMinor: 80000,
    timelinessRewardMinor: 20000, airCoinRewardMinor: 40000,
    diamondRewardMinor: 360000, penaltyMinor: 620000, netMinor: -120000
  })
  assert.equal(buildMissionSettlement('', {}), null)
  assert.match(formatMissionMoney(360000), /3,600/)
})

test('provides six transparent character assets for the three presentation modes', () => {
  assert.deepEqual(Object.keys(MISSION_REACTION_ASSETS), ['diamond', 'fine', 'settlement'])
  assert.equal(MISSION_REACTION_ASSET_PATHS.length, 6)
  assert.equal(new Set(MISSION_REACTION_ASSET_PATHS).size, 6)
  for (const relative of MISSION_REACTION_ASSET_PATHS) {
    const path = join(frontendRoot, 'public', relative.replace(/^\//, ''))
    assert.equal(existsSync(path), true, `${relative} should exist`)
    const descriptor = openSync(path, 'r')
    const header = Buffer.alloc(26)
    try { assert.equal(readSync(descriptor, header, 0, header.length, 0), header.length) }
    finally { closeSync(descriptor) }
    assert.equal(header[25], 6, `${relative} should use RGBA PNG color type`)
  }
})

test('renders character lines in system speech bubbles instead of figure captions', () => {
  const overlay = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'MissionReactionOverlay.vue'), 'utf8')
  assert.doesNotMatch(overlay, /<figcaption\b/)
  assert.match(overlay, /class="character-dialogue reaction-dialogue dialogue-left"/)
  assert.match(overlay, /class="character-dialogue settlement-dialogue dialogue-right"/)
  for (const presentation of Object.values(MISSION_REACTION_PRESENTATION)) {
    assert.ok(presentation.dialogue.anan)
    assert.ok(presentation.dialogue.cheng)
  }
  assert.ok(MISSION_SETTLEMENT_DIALOGUE.anan)
  assert.ok(MISSION_SETTLEMENT_DIALOGUE.cheng)
  assert.equal(MISSION_SETTLEMENT_DIALOGUE.cheng, '任务拿下，背阔肌也得有镜头！')
  assert.doesNotMatch(MISSION_SETTLEMENT_DIALOGUE.cheng, /背肌/)
  assert.match(overlay, /tutorialMissionReactionsSuppressed/)
  assert.match(overlay, /if \(!transaction \|\| runtime\.tutorialMissionReactionsSuppressed\?\.value\) return/)
  assert.match(overlay, /runtime\.tutorialMissionReactionsSuppressed\?\.value \|\| replay/)
})
