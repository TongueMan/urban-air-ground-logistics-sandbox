import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { CHARACTER_MANIFEST } from '../src/tutorial/characterManifest.mjs'
import { calculateConnectorPath, expandRect, shouldAvoidDialogueTarget } from '../src/tutorial/tutorialGeometry.mjs'
import { canAutoResumeTutorial, canReplayTutorial, createManualAttentionRecord, createProgressRecord, createTutorialState, parseManualAttentionRecord, parseProgressRecord, resolveGroundCoopResumeStep, resolveResumeStep, tutorialChapterUnlocked, tutorialReducer, TUTORIAL_PHASES } from '../src/tutorial/tutorialMachine.mjs'
import { FLEET_CENTER_STEPS, FLEET_TUTORIAL_CHAPTER, FLEET_TUTORIAL_STORAGE_KEY, GROUND_COOP_STEPS, GROUND_COOP_TUTORIAL_CHAPTER, PROLOGUE_STEPS, RUN_DEPENDENT_STEPS, TUTORIAL_ATTENTION_STORAGE_KEY, TUTORIAL_CHAPTER, TUTORIAL_CHAPTERS, TUTORIAL_STORAGE_KEY } from '../src/tutorial/tutorialScript.mjs'
import { tutorialFleetIssue } from '../src/tutorial/tutorialReadiness.mjs'
import { buildDialogueSegments } from '../src/tutorial/tutorialText.mjs'
import { fittedMissionRange, missionBoundsMeters, missionOverviewCamera, missionViewportOptions, plannerAwareViewportPoints } from '../src/utils/missionViewport.mjs'

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

test('prologue is driven by the required finite-state phases', () => {
  let state = tutorialReducer(createTutorialState(), { type: 'START' })
  assert.equal(state.phase, TUTORIAL_PHASES.INTRO)
  state = tutorialReducer(state, { type: 'INTRO_FINISHED' })
  assert.equal(state.phase, TUTORIAL_PHASES.DIALOGUE)
  for (let index = 0; index < 6; index += 1) state = tutorialReducer(state, { type: 'ADVANCE' })
  assert.equal(PROLOGUE_STEPS[state.stepIndex].id, 'A01')
  assert.equal(state.phase, TUTORIAL_PHASES.TRANSITION_TO_ACTION)
  state = tutorialReducer(state, { type: 'ACTION_READY' })
  state = tutorialReducer(state, { type: 'ACTION_COMPLETED' })
  assert.equal(state.phase, TUTORIAL_PHASES.ACTION_SUCCESS)
  state = tutorialReducer(state, { type: 'SUCCESS_FINISHED' })
  assert.equal(PROLOGUE_STEPS[state.stepIndex].id, 'D07')
  assert.equal(state.phase, TUTORIAL_PHASES.TRANSITION_TO_DIALOGUE)
})

test('wrong interactions and generation retry remain inside action mode', () => {
  let state = tutorialReducer(createTutorialState({ phase: TUTORIAL_PHASES.ACTION, stepIndex: 10 }), { type: 'WRONG_INTERACTION' })
  state = tutorialReducer(state, { type: 'WRONG_INTERACTION' })
  state = tutorialReducer(state, { type: 'WRONG_INTERACTION' })
  assert.equal(state.wrongAttempts, 3)
  state = tutorialReducer(state, { type: 'ACTION_WAIT' })
  assert.equal(state.waiting, true)
  state = tutorialReducer(state, { type: 'ACTION_RETRY' })
  assert.equal(state.phase, TUTORIAL_PHASES.ACTION)
  assert.equal(state.waiting, false)
})

test('consecutive action and passive waiting steps stay in the action branch', () => {
  const firstActionIndex = PROLOGUE_STEPS.findIndex(step => step.id === 'A02')
  let state = createTutorialState({ phase: TUTORIAL_PHASES.ACTION, stepIndex: firstActionIndex })
  state = tutorialReducer(state, { type: 'ACTION_COMPLETED' })
  state = tutorialReducer(state, { type: 'SUCCESS_FINISHED' })
  assert.equal(PROLOGUE_STEPS[state.stepIndex].id, 'A03')
  assert.equal(state.phase, TUTORIAL_PHASES.TRANSITION_TO_ACTION)

  const waitingIndex = PROLOGUE_STEPS.findIndex(step => step.id === 'WAIT-DIAMOND')
  state = tutorialReducer(createTutorialState({ phase: TUTORIAL_PHASES.DIALOGUE, stepIndex: waitingIndex - 1 }), { type: 'ADVANCE' })
  assert.equal(PROLOGUE_STEPS[state.stepIndex].id, 'WAIT-DIAMOND')
  assert.equal(state.phase, TUTORIAL_PHASES.TRANSITION_TO_ACTION)
  state = tutorialReducer(state, { type: 'ACTION_READY' })
  assert.equal(state.waiting, true)
})

test('chapter 01 uses the same finite-state flow and only asks for the real fleet hub click', () => {
  assert.deepEqual(FLEET_CENTER_STEPS.filter(step => step.mode === 'action').map(step => step.id), ['F01-A01'])
  assert.equal(FLEET_CENTER_STEPS.some(step => /购买|出售/.test(step.completionCondition || '')), false)
  let state = tutorialReducer(createTutorialState(), { type: 'START' }, FLEET_CENTER_STEPS)
  state = tutorialReducer(state, { type: 'INTRO_FINISHED' }, FLEET_CENTER_STEPS)
  state = tutorialReducer(state, { type: 'ADVANCE' }, FLEET_CENTER_STEPS)
  state = tutorialReducer(state, { type: 'ADVANCE' }, FLEET_CENTER_STEPS)
  assert.equal(FLEET_CENTER_STEPS[state.stepIndex].id, 'F01-A01')
  assert.equal(state.phase, TUTORIAL_PHASES.TRANSITION_TO_ACTION)
  state = tutorialReducer(state, { type: 'ACTION_READY' }, FLEET_CENTER_STEPS)
  state = tutorialReducer(state, { type: 'ACTION_COMPLETED' }, FLEET_CENTER_STEPS)
  state = tutorialReducer(state, { type: 'SUCCESS_FINISHED' }, FLEET_CENTER_STEPS)
  assert.equal(FLEET_CENTER_STEPS[state.stepIndex].id, 'F01-D03')
})

test('versioned progress records restore safely and rewind closed planner steps', () => {
  assert.equal(TUTORIAL_STORAGE_KEY, 'skyfleet.tutorial.prologue.v3')
  const context = { taskId: 'TASK-1', runId: 'RUN-1', redVolumeId: 'NFZ-001', redDiamondId: 'D-RED', rewindCheckpointId: 'RWC-1', redFineTransactionId: 'FINE-1', pausedByTutorial: true, resumeTimeScale: 2 }
  const record = createProgressRecord('D08', 'in_progress', 123, TUTORIAL_CHAPTER, context)
  assert.deepEqual(parseProgressRecord(JSON.stringify(record)), record)
  assert.equal(resolveResumeStep(record, { plannerOpen: false }), 'A01')
  assert.equal(resolveResumeStep(record, { plannerOpen: true }), 'D08')
  assert.equal(parseProgressRecord('{broken'), null)
  assert.equal(parseProgressRecord({ version: 1, chapter: TUTORIAL_CHAPTER, stepId: 'D08', status: 'in_progress', updatedAt: 1 }), null)
  assert.equal(parseProgressRecord({ ...record, version: 2 }), null)
  assert.equal(parseProgressRecord({ ...record, version: 99 }), null)
  assert.equal(resolveResumeStep(createProgressRecord('A08-RESTORE', 'in_progress', 124, TUTORIAL_CHAPTER, context), { activeRunId: 'RUN-1' }), 'A07-CHECKPOINT')
  assert.equal(resolveResumeStep(createProgressRecord('A08-RESTORE', 'in_progress', 124, TUTORIAL_CHAPTER, context), { activeRunId: 'RUN-1', rewindCheckpointRestored: true }), 'D14-RETRY')
  assert.equal(resolveResumeStep(createProgressRecord('A08-RESTORE', 'in_progress', 124, TUTORIAL_CHAPTER, context), { activeRunId: 'RUN-OTHER' }), null)
})

test('run-dependent progress resumes only for its authoritative run', () => {
  const running = createProgressRecord('A05', 'in_progress', 123, TUTORIAL_CHAPTER, { taskId: 'TASK-1', runId: 'RUN-1' })
  assert.equal(resolveResumeStep(running, { activeRunId: 'RUN-1' }), 'D12')
  assert.equal(resolveResumeStep(running, { activeRunId: 'RUN-OTHER' }), null)
  assert.equal(resolveResumeStep(running, { activeRunId: '' }), 'A01')
  assert.equal(resolveResumeStep(running, { activeRunId: 'RUN-1', redDetourApplied: true }), 'D15-DETOUR')
  assert.equal(resolveResumeStep(running, { activeRunId: 'RUN-1', redDetourApplied: true, redDiamondCollected: true }), 'D16-REWARD')
  assert.equal(resolveResumeStep(createProgressRecord('A06-CONTINUE', 'in_progress', 123, TUTORIAL_CHAPTER, { runId: 'RUN-1' }), { activeRunId: 'RUN-1' }), 'D12')
  const starting = createProgressRecord('A04', 'in_progress', 123, TUTORIAL_CHAPTER, { taskId: 'TASK-1', runId: 'RUN-1' })
  assert.equal(resolveResumeStep(starting, { activeRunId: 'RUN-1' }), 'A04')
  const following = createProgressRecord('A04-FOLLOW', 'in_progress', 123, TUTORIAL_CHAPTER, { taskId: 'TASK-1', runId: 'RUN-1' })
  assert.equal(RUN_DEPENDENT_STEPS.has('A04-FOLLOW'), true)
  assert.equal(resolveResumeStep(following, { activeRunId: 'RUN-1' }), 'A04-FOLLOW')
})

test('completed and skipped records do not auto-resume, while replay honors running missions', () => {
  assert.equal(resolveResumeStep(createProgressRecord('D12', 'completed')), null)
  assert.equal(resolveResumeStep(createProgressRecord('D04', 'skipped')), null)
  assert.equal(canReplayTutorial({ hasSession: true, isTerminal: false }), false)
  assert.equal(canReplayTutorial({ hasSession: true, isTerminal: false, requestedRunId: 'RUN-1', activeRunId: 'RUN-1' }), true)
  assert.equal(canReplayTutorial({ hasSession: true, isTerminal: true }), true)
  assert.equal(canReplayTutorial({ hasSession: false, isTerminal: false }), true)
})

test('refresh only auto-resumes a tutorial that belongs to the active non-terminal run', () => {
  const preRun = createProgressRecord('A03', 'in_progress', 123)
  const running = createProgressRecord('WAIT-DIAMOND', 'in_progress', 124, TUTORIAL_CHAPTER, { runId: 'RUN-1' })
  assert.equal(canAutoResumeTutorial(preRun, { activeRunId: '' }), false)
  assert.equal(canAutoResumeTutorial(running, { activeRunId: 'RUN-1' }), true)
  assert.equal(canAutoResumeTutorial(running, { activeRunId: 'RUN-1', isTerminal: true }), false)
  assert.equal(canAutoResumeTutorial(running, { activeRunId: 'RUN-2' }), false)
})

test('tutorial fleet readiness only requires deployed equipment because battery is protected', () => {
  const catalog = [
    { typeId: 'ground', category: 'GROUND' },
    { typeId: 'air', category: 'AIR' }
  ]
  const ground = { assetId: 'G-1', typeId: 'ground', status: 'DEPLOYED', batteryPercent: 80, updatedAt: '2026-09-10T00:00:00Z' }
  const air = { assetId: 'A-1', typeId: 'air', status: 'DEPLOYED', batteryPercent: 80, updatedAt: '2026-09-10T00:00:00Z' }
  assert.equal(tutorialFleetIssue({ catalog, assets: [] }).code, 'GROUND_NOT_DEPLOYED')
  assert.equal(tutorialFleetIssue({ catalog, assets: [ground] }).code, 'AIR_NOT_DEPLOYED')
  assert.equal(tutorialFleetIssue({ catalog, assets: [{ ...ground, batteryPercent: 0 }, { ...air, batteryPercent: 0 }] }), null)
  assert.equal(tutorialFleetIssue({ catalog, assets: [ground, air] }, { plan: { economyQuote: { airBatterySufficient: false } } }), null)
  assert.equal(tutorialFleetIssue({ catalog, assets: [ground, air] }), null)
})

test('tutorial generation marks its task for server-side battery protection', () => {
  const planner = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'TaskPlanner.vue'), 'utf8')
  assert.match(planner, /tutorialBatteryProtected: Boolean\(runtime\.tutorialGenerationPreset\.value\)/)
})

test('chapter 01 has independent versioned progress and rewinds when the fleet hub is closed', () => {
  assert.equal(FLEET_TUTORIAL_STORAGE_KEY, 'skyfleet.tutorial.fleet-center.v1')
  const record = createProgressRecord('F01-D07', 'in_progress', 456, FLEET_TUTORIAL_CHAPTER)
  assert.deepEqual(parseProgressRecord(JSON.stringify(record), FLEET_TUTORIAL_CHAPTER), record)
  assert.equal(resolveResumeStep(record, { fleetHubOpen: false, chapterId: FLEET_TUTORIAL_CHAPTER }), 'F01-A01')
  assert.equal(resolveResumeStep(record, { fleetHubOpen: true, chapterId: FLEET_TUTORIAL_CHAPTER }), 'F01-D07')
  assert.equal(parseProgressRecord(record, TUTORIAL_CHAPTER), null)
})

test('chapter 01 unlocks only after the prologue is completed or skipped', () => {
  assert.equal(tutorialChapterUnlocked(FLEET_TUTORIAL_CHAPTER, {}), false)
  assert.equal(tutorialChapterUnlocked(FLEET_TUTORIAL_CHAPTER, { [TUTORIAL_CHAPTER]: 'in_progress' }), false)
  assert.equal(tutorialChapterUnlocked(FLEET_TUTORIAL_CHAPTER, { [TUTORIAL_CHAPTER]: 'completed' }), true)
  assert.equal(tutorialChapterUnlocked(FLEET_TUTORIAL_CHAPTER, { [TUTORIAL_CHAPTER]: 'skipped' }), true)
})

test('manual orders all three chapters and chapter 02 follows server evidence', () => {
  assert.deepEqual(TUTORIAL_CHAPTERS.map(chapter => chapter.index), ['00', '01', '02'])
  assert.equal(tutorialChapterUnlocked(GROUND_COOP_TUTORIAL_CHAPTER, {}), false)
  assert.equal(tutorialChapterUnlocked(GROUND_COOP_TUTORIAL_CHAPTER, { [FLEET_TUTORIAL_CHAPTER]: 'completed' }), true)
  assert.equal(tutorialChapterUnlocked(GROUND_COOP_TUTORIAL_CHAPTER, { [FLEET_TUTORIAL_CHAPTER]: 'skipped' }), true)
  assert.equal(tutorialChapterUnlocked(GROUND_COOP_TUTORIAL_CHAPTER, { [GROUND_COOP_TUTORIAL_CHAPTER]: 'in_progress' }), true)
  assert.deepEqual(GROUND_COOP_STEPS.filter(step => step.mode === 'system').map(step => step.id), ['02-W00-PACE', '02-W01', '02-W02', '02-W03'])
  assert.deepEqual(GROUND_COOP_STEPS.filter(step => step.mode === 'action').map(step => step.id), ['02-A01', '02-A02', '02-A03', '02-A04', '02-A05'])
  assert.equal(GROUND_COOP_STEPS.find(step => step.id === '02-A04')?.allowSelector, '[data-tutorial-ground-reward]')
  assert.equal(GROUND_COOP_STEPS.find(step => step.id === '02-D03-PACE')?.targetId, 'mission-pace-vehicle')
  assert.match(GROUND_COOP_STEPS.find(step => step.id === '02-W00-PACE')?.text || '', /等待合同监管车结束倒计时.*监管车视角/)
  assert.match(GROUND_COOP_STEPS.find(step => step.id === '02-D03-PACE')?.text || '', /合同监管车已经出发.*时限标尺/)
  assert.match(GROUND_COOP_STEPS.find(step => step.id === '02-D03-DEADLINE')?.text || '', /监管车抢先到达.*惩罚.*认证不实际结算/)
  assert.match(GROUND_COOP_STEPS.find(step => step.id === '02-D06')?.text || '', /监管时限内/)
  assert.deepEqual(TUTORIAL_CHAPTERS.map(chapter => chapter.completeTitle), ['教程 00 完成', '教程 01 完成', '教程 02 完成'])
  assert.deepEqual(TUTORIAL_CHAPTERS.map(chapter => chapter.completeKicker), ['调度员已就绪', '车队权限已开放', '进阶规划已就绪'])
  for (const chapter of TUTORIAL_CHAPTERS) {
    assert.doesNotMatch(`${chapter.completeKicker}${chapter.completeTitle}${chapter.completeSubtitle}`, /[A-Za-z]/)
  }
})

test('chapter 02 stays locked until the server grants access and recovers from stale local progress', () => {
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  const runtime = readFileSync(join(frontendRoot, 'src', 'mission', 'runtime', 'useLogisticsMissionRuntime.js'), 'utf8')
  const manual = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialManualEntry.vue'), 'utf8')

  assert.match(engine, /if \(chapterId === GROUND_COOP_TUTORIAL_CHAPTER\) \{\s*return groundTutorialItem\(\)\?\.availability === 'AVAILABLE'/)
  assert.doesNotMatch(engine, /availability === 'AVAILABLE'\s*\|\|\s*tutorialChapterUnlocked/)
  assert.match(engine, /await runtime\.refreshTutorialState\(\)[\s\S]{0,220}!chapterAccessGranted\(chapterId\)/)
  assert.match(engine, /function showLockedGroundTutorial\(\)[\s\S]{0,500}runtime\.closePlanner\(\)[\s\S]{0,500}manualExpanded\.value = true/)
  assert.match(runtime, /tutorialGenerationPreset\.value\?\.tutorialId === 'TUTORIAL-02-GROUND-COOP'[\s\S]{0,120}refreshTutorialState\(\)/)
  assert.match(manual, /:disabled="!chapter\.unlocked"/)
  assert.match(manual, /完成 01 后解锁/)
  assert.match(manual, /教程 02 尚未解锁。请先完成第 01 章/)
  assert.match(manual, /selected\.status === 'new' && selected\.unlocked/)
})

test('chapter 02 pace vehicle explanation points at the waiting vehicle instead of the route HUD', () => {
  const map = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  const bridge = readFileSync(join(frontendRoot, 'src', 'components', 'world', 'MissionWorldBridge.vue'), 'utf8')
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  assert.match(map, /node\.dataset\.tutorialId = 'mission-pace-vehicle'/)
  assert.match(map, /合同监管车 · 等待出发 \$\{departureSeconds\} 秒/)
  assert.doesNotMatch(map, /ground-routing-hud" data-tutorial-id=/)
  assert.match(map, /监管车 \$\{Math\.ceil\(pace\.departureCountdownSeconds\)\} 秒后出发/)
  assert.match(bridge, /:tutorial-camera-target="runtime\.tutorialMapCameraTarget\.value"/)
  assert.match(map, /function showPaceVehicleCloseup\(\)[\s\S]{0,900}engine\.map\.flyTo\(coordinate/)
  assert.match(map, /function paceDeviceForMission\(\)[\s\S]{0,300}deviceType: 'pace_vehicle'/)
  assert.match(map, /updateModelTarget\(paceDevice,[\s\S]{0,500}status === 'RUNNING' \? 'TRANSIT'/)
  assert.match(engine, /\['02-D03-PACE', '02-D03-DEADLINE'\]\.includes\(stepId\)/)
  assert.match(engine, /setTutorialMapCameraTarget\(closeup \? 'pace' : ''\)/)
  assert.match(engine, /stepId === '02-W00-PACE' \? groundPaceDeparted\(\)/)
})

test('chapter 02 refresh recovery maps each authoritative milestone', () => {
  const cases = [
    [{}, '02-A01'],
    [{ baselineSelected: true }, '02-A03'],
    [{ taskStarted: true }, '02-W00-PACE'],
    [{ routeOutsideReward: true }, '02-A05'],
    [{ returnedToBaseline: true }, '02-W01'],
    [{ uavTakeoff: true }, '02-W02'],
    [{ uavRecovered: true }, '02-W03'],
    [{ missionCompleted: true }, '02-D06']
  ]
  for (const [evidence, expected] of cases) assert.equal(resolveGroundCoopResumeStep(evidence), expected)
  const record = createProgressRecord('02-A04', 'in_progress', 1, GROUND_COOP_TUTORIAL_CHAPTER, { runId: 'RUN-02' })
  assert.equal(resolveResumeStep(record, { chapterId: GROUND_COOP_TUTORIAL_CHAPTER, activeRunId: 'RUN-02', groundEvidence: { uavTakeoff: true } }), '02-W02')
  assert.equal(resolveResumeStep(record, { chapterId: GROUND_COOP_TUTORIAL_CHAPTER, activeRunId: 'RUN-OTHER' }), null)
  assert.equal(resolveResumeStep(record, { chapterId: GROUND_COOP_TUTORIAL_CHAPTER, activeRunId: '' }), '02-D01')
})

test('chapter 02 owns skip confirmation, 5x observation and settlement suppression', () => {
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  const reactions = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'MissionReactionOverlay.vue'), 'utf8')
  assert.match(engine, /syncTutorialStatus\(GROUND_COOP_TUTORIAL_CHAPTER, GROUND_COOP_TUTORIAL_VERSION, 'SKIPPED'\)/)
  assert.match(engine, /runningGroundTutorial[\s\S]{0,180}runtime\.endMission\(\)/)
  assert.match(engine, /resumeMission\(5\)/)
  assert.match(engine, /setTutorialMissionReactionsSuppressed\(true\)/)
  assert.match(overlay, /确认跳过联合配送资格认证/)
  assert.match(overlay, /确认跳过并解锁/)
  assert.match(reactions, /!runtime\.tutorialMissionReactionsSuppressed\?\.value && isLiveMissionCompletion/)
})

test('manual attention acknowledgement is versioned and survives refresh safely', () => {
  assert.equal(TUTORIAL_ATTENTION_STORAGE_KEY, 'skyfleet.tutorial.manual-attention.v1')
  const record = createManualAttentionRecord(789)
  assert.deepEqual(record, { version: 1, seen: true, updatedAt: 789 })
  assert.deepEqual(parseManualAttentionRecord(JSON.stringify(record)), record)
  assert.equal(parseManualAttentionRecord('{broken'), null)
  assert.equal(parseManualAttentionRecord({ ...record, seen: false }), null)
  assert.equal(parseManualAttentionRecord({ ...record, version: 99 }), null)
})

test('spotlight expansion clamps to the viewport', () => {
  assert.deepEqual(expandRect({ left: 4, top: 5, width: 20, height: 10 }, 12, { width: 100, height: 80 }), {
    left: 0, top: 0, right: 36, bottom: 27, width: 36, height: 27
  })
})

test('dialogue avoidance protects compact targets without oscillating on full-map highlights', () => {
  const dialogue = { left: 200, top: 560, width: 900, height: 150 }
  assert.equal(shouldAvoidDialogueTarget({ left: 20, top: 620, width: 240, height: 80 }, dialogue, { width: 1366, height: 768 }), true)
  assert.equal(shouldAvoidDialogueTarget({ left: 0, top: 0, width: 1366, height: 768 }, dialogue, { width: 1366, height: 768 }), false)
})

test('mission viewport uses the tighter bounded range and planner-safe west padding', () => {
  const points = [[117.20, 31.80, 0], [117.21, 31.805, 80]]
  const bounds = missionBoundsMeters(points)
  assert.ok(bounds.extent > 500)
  assert.equal(fittedMissionRange([[117.2, 31.8], [117.2001, 31.8001]]), 500)
  assert.equal(fittedMissionRange([[100, 20], [120, 40]]), 12500)
  assert.ok(Math.abs(fittedMissionRange(points) - bounds.extent * 1.35) < 1e-6)
  const padded = plannerAwareViewportPoints(points, { panelWidth: 500, viewportWidth: 1366 })
  assert.equal(padded.length, 3)
  assert.ok(padded.at(-1)[0] < Math.min(...points.map(point => point[0])))
  assert.deepEqual(missionViewportOptions(), { range: 120, zoom: 0 })
  assert.deepEqual(missionViewportOptions({ range: 999, zoom: 9 }), { range: 600, zoom: 2 })
  assert.deepEqual(missionOverviewCamera(points), {
    center: bounds.center,
    heading: 12,
    pitch: 70,
    range: fittedMissionRange(points)
  })
  assert.equal(missionOverviewCamera([]), null)
})

test('prologue teaches real device following and the three desktop map gestures before airspace handling', () => {
  const follow = PROLOGUE_STEPS.find(step => step.id === 'A04-FOLLOW')
  const overview = PROLOGUE_STEPS.find(step => step.id === 'A04-OVERVIEW')
  const mouse = PROLOGUE_STEPS.find(step => step.id === 'D11-MOUSE')
  assert.deepEqual([follow?.targetId, follow?.completionCondition], ['mission-device-list', 'device-following'])
  assert.deepEqual([overview?.targetId, overview?.completionCondition], ['return-mission-overview', 'mission-overview-restored'])
  for (const phrase of ['鼠标左键', '滚轮可缩放', '按住滚轮']) assert.match(mouse?.text || '', new RegExp(phrase))
  assert.ok(PROLOGUE_STEPS.findIndex(step => step.id === 'D11-MOUSE') < PROLOGUE_STEPS.findIndex(step => step.id === 'D12'))
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  assert.match(overlay, /is-device-follow-step/)
  assert.match(overlay, /is-live-aware/)
  assert.match(overlay, /left:clamp\(440px,32vw,620px\)/)
  assert.doesNotMatch(overlay, /is-device-follow-step :deep\(\.tutorial-dialogue\)\{[^}]*top:112px/)
})

test('prologue explains the operating role, platform purpose and ready starter fleet before the first action', () => {
  const opening = PROLOGUE_STEPS.slice(0, 6).map(step => step.text).join('\n')
  for (const phrase of ['城市空地协同物流运营中心', '新任运营调度负责人', '订单、车辆、无人机与数字空域', '城市地图就是你的指挥台', '满电', '均已出站', '时效、电量、资金与空域安全']) {
    assert.match(opening, new RegExp(phrase))
  }
  assert.equal(PROLOGUE_STEPS.slice(0, 6).every(step => step.mode === 'dialogue'), true)
  assert.equal(PROLOGUE_STEPS[6].id, 'A01')
})

test('connector geometry produces a bounded cubic path for horizontal and vertical targets', () => {
  const horizontal = calculateConnectorPath(
    { left: 20, top: 120, width: 50, height: 30 },
    { left: 500, top: 400, width: 300, height: 90 },
    { width: 1000, height: 700 }
  )
  const vertical = calculateConnectorPath(
    { left: 650, top: 30, width: 100, height: 40 },
    { left: 500, top: 500, width: 300, height: 90 },
    { width: 1000, height: 700 }
  )
  assert.match(horizontal, /^M \d+\.\d \d+\.\d C /)
  assert.match(vertical, /^M \d+\.\d \d+\.\d C /)
  assert.doesNotMatch(`${horizontal}${vertical}`, /NaN|Infinity/)
})

test('character manifest provides all eight real-alpha PNG expressions', () => {
  const expected = {
    anan: ['default', 'greeting', 'guide', 'awkward'],
    cheng: ['default', 'analysis', 'confident', 'facepalm']
  }
  for (const [actor, expressions] of Object.entries(expected)) {
    assert.deepEqual(Object.keys(CHARACTER_MANIFEST[actor].expressions), expressions)
    for (const expression of expressions) {
      const relative = CHARACTER_MANIFEST[actor].expressions[expression]
      const path = join(frontendRoot, 'public', relative.replace(/^\//, ''))
      assert.equal(existsSync(path), true, `${relative} should exist`)
      const png = readFileSync(path)
      assert.equal(png[25], 6, `${relative} should use RGBA PNG color type`)
    }
  }
})

test('dialogue emphasis remains data-driven and safe during typewriter reveal', () => {
  assert.deepEqual(buildDialogueSegments('相同 Seed 可以复现', [
    { text: 'Seed', tone: 'amber' },
    { text: '复现', tone: 'cyan' }
  ]), [
    { text: '相同 ', tone: null },
    { text: 'Seed', tone: 'amber' },
    { text: ' 可以', tone: null },
    { text: '复现', tone: 'cyan' }
  ])
  assert.deepEqual(buildDialogueSegments('相同 See', [{ text: 'Seed', tone: 'amber' }]), [
    { text: '相同 See', tone: null }
  ])
})

test('real mission elements expose every v3 tutorial contract', () => {
  const missionState = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'MissionState.vue'), 'utf8')
  const taskPlanner = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'TaskPlanner.vue'), 'utf8')
  const missionMap = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  const fleetDock = readFileSync(join(frontendRoot, 'src', 'components', 'fleet', 'FleetDock.vue'), 'utf8')
  const airspacePanel = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'AirspacePanel.vue'), 'utf8')
  const missionTimeline = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'MissionTimeline.vue'), 'utf8')
  const instrumentCluster = readFileSync(join(frontendRoot, 'src', 'components', 'instruments', 'InstrumentCluster.vue'), 'utf8')
  const source = `${missionState}\n${taskPlanner}\n${missionMap}\n${fleetDock}\n${airspacePanel}\n${missionTimeline}\n${instrumentCluster}`
  for (const id of ['create-mission', 'mission-planner', 'mission-area', 'mission-zone-count', 'mission-zone-count-four', 'generate-mission', 'mission-preview', 'start-mission', 'mission-control', 'mission-device-list', 'mission-device', 'mission-map-interaction', 'return-mission-overview', 'red-airspace-conflict', 'airspace-inspector', 'airspace-keep-course', 'mission-timeline', 'mission-rewind-checkpoint', 'mission-rewind-restore', 'airspace-detour', 'mission-battery']) {
    assert.match(source, new RegExp(id))
  }
})

test('fleet center exposes stable tutorial contracts without purchase or sale actions in the script', () => {
  const fleetDock = readFileSync(join(frontendRoot, 'src', 'components', 'fleet', 'FleetDock.vue'), 'utf8')
  const fleetHub = readFileSync(join(frontendRoot, 'src', 'components', 'fleet', 'FleetHub.vue'), 'utf8')
  const source = `${fleetDock}\n${fleetHub}`
  for (const id of ['fleet-hub-entry', 'fleet-hub', 'fleet-categories', 'fleet-balance', 'fleet-catalog', 'fleet-detail', 'fleet-purchase', 'fleet-instances', 'fleet-deployment']) {
    assert.match(source, new RegExp(`data-tutorial-id=["']${id}["']`))
  }
})

test('runtime integration owns only its pause and waits for authoritative detour and diamond state', () => {
  const runtime = readFileSync(join(frontendRoot, 'src', 'mission', 'runtime', 'useLogisticsMissionRuntime.js'), 'utf8')
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  assert.match(runtime, /initialized: readonly\(initialized\)/)
  assert.match(engine, /MutationObserver/)
  assert.match(engine, /ResizeObserver/)
  assert.match(engine, /TARGET_DELAY_NOTICE = 12000/)
  assert.match(engine, /runtime\.taskPreview\.value/)
  assert.match(engine, /runtime\.pauseMission\(\)/)
  assert.match(engine, /releaseTutorialPause/)
  assert.match(engine, /lastAirspaceAction/)
  assert.match(engine, /latestEconomyTransaction/)
  assert.match(engine, /lastRewindAck/)
  assert.match(engine, /RED_FINE_REACTION_DURATION = 3500/)
  assert.match(engine, /collectedDiamondIds/)
  assert.match(engine, /PROLOGUE_TUTORIAL_SEED/)
  assert.match(engine, /mapFollowingDeviceId/)
  assert.match(engine, /runtime\.context\.focusActor\(deviceId\)/)
  assert.match(engine, /function focusRedCourseDrone\(\)/)
  assert.match(engine, /actor => actor\.kind === 'UAV'/)
  assert.match(engine, /droneId && runtime\.context\?\.focusActor\(droneId\)/)
  assert.match(engine, /function restoreRedCourseOverview\(stepId = currentStep\.value\?\.id\)/)
  assert.match(engine, /stepId !== 'D13-REWIND'/)
  assert.match(engine, /runtime\.context\.clearFocus\(\)/)
  assert.match(runtime, /mapFollowingDeviceId: readonly\(mapFollowingDeviceId\)/)
  assert.match(runtime, /tutorialRedConflictLocked: readonly\(tutorialRedConflictLocked\)/)
  assert.match(runtime, /tutorialRewindLocked: readonly\(tutorialRewindLocked\)/)
  assert.match(runtime, /tutorialMissionReactionsSuppressed: readonly\(tutorialMissionReactionsSuppressed\)/)
  assert.match(engine, /configureProloguePresentationGuards/)
  assert.match(engine, /runtime\.setTutorialRedConflictLocked\(true\)[\s\S]{0,120}runtime\.closeAirspace\(\)[\s\S]{0,80}completeAction\(\)/)
  const observation = engine.match(/async function beginRedCourseObservation\(\) \{[\s\S]*?\n  \}/)?.[0] || ''
  assert.match(observation, /currentStep\.value\.id !== 'WAIT-RED-VIOLATION'/)
  assert.match(observation, /focusRedCourseDrone\(\)/)
  assert.ok(observation.indexOf('focusRedCourseDrone()') < observation.indexOf('await releaseTutorialPause()'))
  assert.match(observation, /await releaseTutorialPause\(\)/)
  const closeChapter = engine.match(/function closeActiveChapter\([^]*?\n  \}/)?.[0] || ''
  assert.match(closeChapter, /clearTutorialRedConflictLocked/)
  assert.match(closeChapter, /clearTutorialMissionReactionsSuppressed/)
  assert.doesNotMatch(engine, /claimTutorialReward|rewardState|5 万元/)
  assert.match(engine, /fleetRuntime\.isOpen\.value/)
  assert.doesNotMatch(engine, /startPreview\s*\(/)
  assert.match(engine, /selectedChapterId\.value = \(next \|\| resumable \|\| fallback \|\| TUTORIAL_CHAPTERS\[0\]\)\.id[\s\S]{0,300}manualExpanded\.value = false/)
})

test('prologue locks timeline history until A07 and self-heals premature rewind state', () => {
  const runtime = readFileSync(join(frontendRoot, 'src', 'mission', 'runtime', 'useLogisticsMissionRuntime.js'), 'utf8')
  const timeline = readFileSync(join(frontendRoot, 'src', 'components', 'mission', 'MissionTimeline.vue'), 'utf8')
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  assert.match(runtime, /function setTutorialRewindLocked\(locked = true\)/)
  assert.match(runtime, /tutorialRewindLocked\.value && context\.timeMode\.value === 'REPLAY'/)
  for (const control of [':disabled="rewindLocked"', 'runtime.timeControlBusy.value || rewindLocked', 'runtime.rewindBusy.value || rewindLocked']) {
    assert.match(timeline, new RegExp(control.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
  assert.match(timeline, /!rewindLocked && checkpoint\.available/)
  assert.match(engine, /step === 'A07-CHECKPOINT'[\s\S]{0,240}TUTORIAL_PHASES\.ACTION/)
  assert.match(engine, /function recoverPrematureCheckpointRestore\(\)/)
  assert.match(engine, /String\(checkpoint\.status \|\| ''\) === 'USED'/)
  assert.match(engine, /function completeAlreadyPreviewedCheckpoint\(\)/)
  assert.match(engine, /Math\.abs\(cursor - Number\(checkpoint\.progress \|\| 0\)\) > 0\.35/)
  assert.match(engine, /attributeFilter: \['class', 'style', 'disabled', 'data-tutorial-id'\]/)
})

test('dense tutorial scenes render only the speaker and action guidance uses an inset avatar', () => {
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  const character = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialCharacter.vue'), 'utf8')
  const dialogue = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialDialogue.vue'), 'utf8')
  assert.match(overlay, /v-else-if="showTutorialCast"/)
  assert.match(overlay, /TUTORIAL_PHASES\.DIALOGUE,[\s\S]{0,80}TUTORIAL_PHASES\.ACTION/)
  assert.match(overlay, /v-if="showAnan && !engine\.isActionMode\.value"/)
  assert.match(overlay, /v-if="showCheng && !engine\.isActionMode\.value"/)
  assert.match(overlay, /const duoCast = computed\([\s\S]{0,300}!engine\.currentStep\.value\.targetId/)
  const timelineRule = overlay.match(/\.tutorial-presentation\.is-timeline-step :deep\(\.tutorial-dialogue\)\{[^}]+\}/)?.[0] || ''
  assert.match(timelineRule, /bottom:84px/)
  assert.match(timelineRule, /left:50%/)
  assert.doesNotMatch(timelineRule, /top:112px|right:24px/)
  assert.match(dialogue, /v-if="actionMode" class="dialogue-avatar"/)
  assert.match(dialogue, /\.dialogue-avatar\{[^}]*width:54px[^}]*height:54px/)
  assert.match(overlay, /:expression="engine\.expression\.value"/)
  assert.match(character, /bottom: 18px/)
  assert.match(character, /calc\(50% - 570px\)/)
})

test('airspace inspector keeps its business positioning and reserves a tutorial safe area', () => {
  const dialogue = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialDialogue.vue'), 'utf8')
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  const inspectorRule = dialogue.match(/\.tutorial-dialogue\.is-inspector\{[^}]+\}/)?.[0] || ''
  assert.match(inspectorRule, /left:50%/)
  assert.match(inspectorRule, /width:min\(960px,calc\(100% - 24px\)\)/)
  assert.match(inspectorRule, /transform:translateX\(-50%\)/)
  assert.match(overlay, /is-live-aware\.is-inspector-aware \.tutorial-stage\{right:clamp\(360px,21vw,400px\)\}/)
  assert.doesNotMatch(overlay, /tutorial-target-active[^}]*position:relative/)
})

test('airspace conflict card advertises its click action and links to the inspector after rewind', () => {
  const missionMap = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  assert.match(missionMap, /点击查看处置/)
  assert.match(missionMap, /is-click-inviting/)
  assert.match(missionMap, /conflict-card-invite 1\.2s ease-in-out 3/)
  assert.match(missionMap, /viewedConflictKeys/)
  assert.match(overlay, /engine\.currentStep\.value\.id === 'D14-INSPECT'/)
  assert.match(overlay, /tutorial-relation-arrow/)
  assert.match(overlay, /marker-end="url\(#tutorial-relation-arrow\)"/)
  assert.match(overlay, /calculateConnectorPath\(relationTargetRect\.value, relationSourceRect\.value, viewport\)/)
  assert.match(overlay, /relation-source-frame/)
})

test('tutorial dialogue stays centered inside context-aware stage safe areas', () => {
  const dialogue = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialDialogue.vue'), 'utf8')
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  const baseRule = dialogue.match(/\.tutorial-dialogue\{[^}]+\}/)?.[0] || ''
  assert.match(baseRule, /left:50%/)
  assert.match(baseRule, /transform:translateX\(-50%\)/)
  const groupedRule = dialogue.match(/\.tutorial-dialogue\.is-planner,\.tutorial-dialogue\.is-inspector\{[^}]+\}/)?.[0] || ''
  const fleetRule = dialogue.match(/\.tutorial-dialogue\.is-fleet\{[^}]+\}/)?.[0] || ''
  const timelineRule = overlay.match(/\.tutorial-presentation\.is-timeline-step :deep\(\.tutorial-dialogue\)\{[^}]+\}/)?.[0] || ''
  for (const rule of [groupedRule, fleetRule, timelineRule]) {
    assert.match(rule, /right:auto/)
    assert.match(rule, /left:50%/)
    assert.match(rule, /transform:translateX\(-50%\)/)
  }
  assert.match(overlay, /is-planner-aware \.tutorial-stage\{left:clamp\(448px,30vw,530px\)\}/)
  assert.match(overlay, /is-live-aware:not\(\.is-planner-aware\) \.tutorial-stage\{left:clamp\(440px,32vw,620px\);right:clamp\(292px,19vw,380px\)\}/)
  assert.match(overlay, /max-width:1600px\) and \(max-height:900px\)[\s\S]{0,260}\.tutorial-dialogue\)\{bottom:88px\}/)
  assert.doesNotMatch(dialogue, /calc\(100vw - (?:760|400)px\)/)
})

test('v3 script teaches a real red-zone mistake, checkpoint rewind, conflict inspection and corrected detour', () => {
  assert.equal(PROLOGUE_STEPS.some(step => /高级参数|稳定比炫技/.test(step.text)), false)
  assert.deepEqual(PROLOGUE_STEPS.filter(step => step.mode === 'action').map(step => step.id), ['A01', 'A02', 'A03', 'A04', 'A04-FOLLOW', 'A04-OVERVIEW', 'A07-CHECKPOINT', 'A08-RESTORE', 'A09-CONFLICT', 'A09-DETOUR'])
  const ids = PROLOGUE_STEPS.map(step => step.id)
  assert.ok(ids.indexOf('D12') < ids.indexOf('WAIT-RED-VIOLATION'))
  assert.ok(ids.indexOf('WAIT-RED-VIOLATION') < ids.indexOf('A07-CHECKPOINT'))
  assert.ok(ids.indexOf('A07-CHECKPOINT') < ids.indexOf('A08-RESTORE'))
  assert.ok(ids.indexOf('A08-RESTORE') < ids.indexOf('A09-CONFLICT'))
  assert.ok(ids.indexOf('A09-CONFLICT') < ids.indexOf('D14-INSPECT'))
  assert.ok(ids.indexOf('D14-INSPECT') < ids.indexOf('A09-DETOUR'))
  assert.deepEqual([
    PROLOGUE_STEPS.find(step => step.id === 'A07-CHECKPOINT')?.targetId,
    PROLOGUE_STEPS.find(step => step.id === 'A08-RESTORE')?.targetId,
    PROLOGUE_STEPS.find(step => step.id === 'A09-CONFLICT')?.targetId,
    PROLOGUE_STEPS.find(step => step.id === 'D14-INSPECT')?.targetId,
    PROLOGUE_STEPS.find(step => step.id === 'A09-DETOUR')?.targetId
  ], ['mission-rewind-checkpoint', 'mission-rewind-restore', 'red-airspace-conflict', 'airspace-inspector', 'airspace-detour'])
  assert.equal(PROLOGUE_STEPS.find(step => step.id === 'A09-CONFLICT')?.completionCondition, 'red-airspace-inspected')
  assert.equal(PROLOGUE_STEPS.find(step => step.id === 'D14-RETRY')?.targetId, undefined)
  assert.match(PROLOGUE_STEPS.find(step => step.id === 'D14-RETRY')?.text || '', /接下来地图会重新给出.*空域冲突/)
  assert.equal(PROLOGUE_STEPS.find(step => step.id === 'D12')?.targetId, undefined)
  assert.match(PROLOGUE_STEPS.find(step => step.id === 'D12')?.text || '', /锁定提前处置/)
  assert.equal(PROLOGUE_STEPS.find(step => step.id === 'WAIT-RED-VIOLATION').completionCondition, 'red-fine-and-checkpoint-ready')
  assert.match(PROLOGUE_STEPS.find(step => step.id === 'WAIT-RED-VIOLATION').text, /视角已切到无人机正后方/)
  assert.equal(PROLOGUE_STEPS.find(step => step.id === 'WAIT-DIAMOND').completionCondition, 'red-diamond-collected')
  assert.match(PROLOGUE_STEPS.find(step => step.id === 'D16-REWARD').text, /¥4,800/)
  const overlay = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialOverlay.vue'), 'utf8')
  const engine = readFileSync(join(frontendRoot, 'src', 'tutorial', 'useTutorialEngine.js'), 'utf8')
  const map = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(overlay, /is-timeline-step/)
  assert.match(engine, /currentStep\.value\.id === 'A09-CONFLICT'[\s\S]{0,180}redInspectorOpen\(\)/)
  assert.match(engine, /currentStep\.value\.id === 'A09-CONFLICT'[\s\S]{0,320}runtime\.selectAirspace\(redVolumeId\)/)
  assert.match(engine, /const RED_CONFLICT_UNLOCKED_STEPS = new Set\(\[\s*'A09-CONFLICT'/)
  assert.doesNotMatch(engine, /RED_CONFLICT_UNLOCKED_STEPS = new Set\(\[[\s\S]{0,80}'D14-RETRY'/)
  assert.match(engine, /function completeRewindAction[\s\S]{0,900}setTutorialRedConflictLocked\(true\)[\s\S]{0,120}closeAirspace\(\)/)
  assert.match(engine, /function airspaceInspectorMustStayClosed[\s\S]{0,240}!RED_CONFLICT_UNLOCKED_STEPS\.has\(step\)/)
  assert.match(engine, /watch\(\(\) => runtime\.selectedAirspaceId\.value[\s\S]{0,420}airspaceInspectorMustStayClosed\(\)[\s\S]{0,120}runtime\.closeAirspace\(\)/)
  assert.match(engine, /const AIRSPACE_FREE_STEPS = new Set\(\['D16-REWARD', 'D17-BATTERY', 'D18-COMPLETE'\]\)/)
  assert.match(engine, /if \(airspaceInspectorMustStayClosed\(step\)\) runtime\.closeAirspace\(\)/)
  assert.match(map, /airspace-conflict-marker\.tutorial-target-active/)
  assert.match(map, /@keyframes tutorial-conflict-invite/)
  assert.match(map, /tutorial-conflict-invite \.9s ease-in-out 3,tutorial-conflict-breathe 2\.4s ease-in-out 2\.7s infinite/)
  assert.match(map, /prefers-reduced-motion:reduce[\s\S]{0,420}airspace-conflict-marker\.tutorial-target-active/)
})

test('clearing a focused device restores the mission overview camera', () => {
  const map = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(map, /function showMissionOverview\(\)/)
  assert.match(map, /if \(!deviceId\) \{ followingId\.value = ''; followZoomScale = 1; showMissionOverview\(\); return \}/)
  assert.match(map, /const contextWillRestoreOverview = Boolean\(props\.selectedId\)/)
  assert.match(map, /if \(!contextWillRestoreOverview\) showMissionOverview\(\)/)
  assert.match(map, /if \(!fitted && viewportPoints\.length > 1\) \{\s*\/\/[\s\S]{0,420}if \(!flyToMissionOverview\(viewportPoints\)\)/)
  assert.doesNotMatch(map, /!props\.planningPreview \|\| !flyToMissionOverview\(viewportPoints\)/)
})

test('tutorial entry uses a prominent readable card and the map legend clears it', () => {
  const beacon = readFileSync(join(frontendRoot, 'src', 'tutorial', 'TutorialBeacon.vue'), 'utf8')
  const map = readFileSync(join(frontendRoot, 'src', 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(beacon, /width:232px;height:68px/)
  assert.match(beacon, /\.beacon-copy small\{[^}]*font-size:\.75rem/)
  assert.match(beacon, /MISSION MANUAL/)
  assert.match(beacon, /beacon-pointer/)
  assert.match(beacon, /is-attention/)
  assert.match(beacon, /prefers-reduced-motion:reduce/)
  assert.match(map, /id="mission-map-legend" class="map-legend"/)
  assert.match(map, /\.map-legend \{[^}]*right:24px; bottom:104px/)
  assert.doesNotMatch(map, /map-legend-toggle|legendOpen/)
})
