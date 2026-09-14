import { computed, nextTick, onBeforeUnmount, onMounted, readonly, ref, watch } from 'vue'
import { expandRect } from './tutorialGeometry.mjs'
import {
  canAutoResumeTutorial,
  canReplayTutorial,
  createManualAttentionRecord,
  createProgressRecord,
  createTutorialState,
  parseManualAttentionRecord,
  parseProgressRecord,
  resolveResumeStep,
  tutorialChapterUnlocked,
  tutorialReducer,
  TUTORIAL_PHASES
} from './tutorialMachine.mjs'
import {
  AIRSPACE_PANEL_DEPENDENT_STEPS,
  FLEET_HUB_DEPENDENT_STEPS,
  FLEET_TUTORIAL_CHAPTER,
  GROUND_COOP_PLANNER_STEPS,
  GROUND_COOP_TUTORIAL_CHAPTER,
  GROUND_COOP_TUTORIAL_VERSION,
  PLANNER_DEPENDENT_STEPS,
  PROLOGUE_TUTORIAL_SEED,
  RUN_DEPENDENT_STEPS,
  TUTORIAL_ATTENTION_STORAGE_KEY,
  TUTORIAL_CHAPTER,
  TUTORIAL_CHAPTERS,
  getTutorialChapter
} from './tutorialScript.mjs'
import { tutorialFleetIssue } from './tutorialReadiness.mjs'

const INTRO_DURATION = 550
const ACTION_TRANSITION = 260
const SUCCESS_RIPPLE = 420
const DIALOGUE_TRANSITION = 180
const COMPLETE_DURATION = 1500
const TARGET_DELAY_NOTICE = 12000
const RED_FINE_REACTION_DURATION = 3500
const REWIND_INTERACTION_STEPS = new Set(['A07-CHECKPOINT', 'A08-RESTORE'])
const PREMATURE_REWIND_RECOVERY_STEPS = new Set(['WAIT-RED-VIOLATION', 'D13-REWIND', 'A07-CHECKPOINT', 'A08-RESTORE'])
const RED_INSPECTOR_REQUIRED_STEPS = new Set(['D14-INSPECT', 'A09-DETOUR'])
const AIRSPACE_FREE_STEPS = new Set(['D16-REWARD', 'D17-BATTERY', 'D18-COMPLETE'])

export function useTutorialEngine(runtime, fleetRuntime) {
  const state = ref(createTutorialState())
  const activeChapterId = ref('')
  const selectedChapterId = ref(TUTORIAL_CHAPTER)
  const progressRecords = ref({})
  const manualExpanded = ref(false)
  const displayedText = ref('')
  const typingComplete = ref(true)
  const targetElement = ref(null)
  const targetRect = ref(null)
  const targetSyncDelayed = ref(false)
  const wrongPulseKey = ref(0)
  const reducedMotion = ref(false)
  const generationFailed = ref(false)
  const startFailed = ref(false)
  const detourFailed = ref(false)
  const redCourseFailed = ref(false)
  const rewindFailed = ref(false)
  const chapterPreparing = ref(false)
  const manualAttentionSeen = ref(false)
  const progressContext = ref({})
  const skipConfirmOpen = ref(false)
  const skipError = ref('')
  const skipInFlight = ref(false)

  let typewriterTimer = null
  let transitionTimer = null
  let targetDelayTimer = null
  let targetMutationObserver = null
  let targetResizeObserver = null
  let motionQuery = null
  let initialized = false
  let previewBeforeAction = null
  let generationStarted = false
  let startActionStarted = false
  let detourActionStarted = false
  let pauseInFlight = false
  let detourCompletionInFlight = false
  let redCourseInFlight = false
  let redCourseObservationStarted = false
  let redFinePauseInFlight = false
  let rewindActionStarted = false
  let fleetRecoveryInFlight = false
  let groundMilestoneInFlight = false

  const phase = computed(() => state.value.phase)
  const selectedChapter = computed(() => getTutorialChapter(selectedChapterId.value))
  const activeChapter = computed(() => getTutorialChapter(activeChapterId.value || selectedChapterId.value))
  const steps = computed(() => activeChapter.value.steps)
  const currentStep = computed(() => steps.value[state.value.stepIndex] || steps.value[0])
  const isActive = computed(() => phase.value !== TUTORIAL_PHASES.CLOSED && Boolean(activeChapterId.value))
  const isActionMode = computed(() => [
    TUTORIAL_PHASES.TRANSITION_TO_ACTION,
    TUTORIAL_PHASES.ACTION,
    TUTORIAL_PHASES.ACTION_SUCCESS
  ].includes(phase.value))
  const locksInteraction = computed(() => phase.value === TUTORIAL_PHASES.ACTION && currentStep.value?.mode === 'action')
  const plannerAware = computed(() => [TUTORIAL_CHAPTER, GROUND_COOP_TUTORIAL_CHAPTER].includes(activeChapterId.value) && Boolean(runtime.plannerOpen.value))
  const fleetAware = computed(() => activeChapterId.value === FLEET_TUTORIAL_CHAPTER && Boolean(fleetRuntime?.isOpen.value))
  const selectedProgress = computed(() => progressRecords.value[selectedChapterId.value] || null)
  const replayAllowed = computed(() => canReplayTutorial({
    hasSession: runtime.hasSession.value,
    isTerminal: runtime.isTerminal.value,
    requestedRunId: selectedProgress.value?.status === 'in_progress' ? selectedProgress.value?.runId : '',
    activeRunId: runtime.session.value?.id
  }))
  const ready = computed(() => Boolean(runtime.initialized.value))
  const statuses = computed(() => Object.fromEntries(TUTORIAL_CHAPTERS.map(chapter => [
    chapter.id,
    progressRecords.value[chapter.id]?.status || 'new'
  ])))
  const progressStatus = computed(() => statuses.value[selectedChapterId.value] || 'new')
  const chapterCards = computed(() => TUTORIAL_CHAPTERS.map(chapter => ({
    ...chapter,
    status: statuses.value[chapter.id] || 'new',
    unlocked: chapterAccessGranted(chapter.id, statuses.value)
  })))
  const beaconChapterIndex = computed(() => {
    const next = chapterCards.value.find(chapter => chapter.unlocked && ['new', 'in_progress'].includes(chapter.status))
    return (next || chapterCards.value.at(-1)).index
  })
  const beaconAttention = computed(() => ready.value && !manualAttentionSeen.value && !manualExpanded.value && !isActive.value)
  const tracksTarget = computed(() => Boolean(
    isActive.value &&
    currentStep.value?.targetId &&
    (currentStep.value.spotlight || currentStep.value.mode === 'action')
  ))
  const spotlightRect = computed(() => targetRect.value
    ? expandRect(targetRect.value, 12, { width: window.innerWidth, height: window.innerHeight })
    : null)
  const groundRewardOnBaseline = computed(() => {
    if (activeChapterId.value !== GROUND_COOP_TUTORIAL_CHAPTER || currentStep.value?.id !== '02-A04') return false
    const mission = activeMission()
    const targetId = String(mission?.groundRouting?.activeTemporaryTarget?.targetId || '')
    const reward = (mission?.groundRewards || []).find(item => String(item.id || '') === targetId)
    return Boolean(targetId && reward?.eligibleCandidateIds?.map(String)
      .includes(String(mission?.groundRouting?.baselineRouteCandidateId || '')) && !groundTutorialEvidence().routeOutsideReward)
  })
  const speaker = computed(() => groundRewardOnBaseline.value ? 'cheng' : state.value.wrongAttempts >= 3 && isActionMode.value
    ? 'anan'
    : currentStep.value?.speaker)
  const expression = computed(() => groundRewardOnBaseline.value ? 'analysis' : state.value.wrongAttempts >= 3 && isActionMode.value
    ? 'awkward'
    : currentStep.value?.expression)
  const effectiveText = computed(() => {
    if (targetSyncDelayed.value) {
      if (REWIND_INTERACTION_STEPS.has(currentStep.value.id)) return '正在核对关键节点状态。界面准备好后会自动继续，不需要重复点击。'
      if (currentStep.value.id === '02-A04') return '地面奖励点仍在同步，请稍候；目标出现后可以直接重试。'
      return '目标仍在同步，请稍候。界面准备好后会自动继续。'
    }
    if (groundRewardOnBaseline.value) return '这处奖励就在当前基线上，车辆没有真正改变执行路线。换一处属于其他候选路线的奖励，再试一次。'
    if (generationFailed.value && ['A03', '02-A01'].includes(currentStep.value.id)) return '方案没有通过这次校验。检查系统提示后，再点击一次生成本局任务。'
    if (startFailed.value && ['A04', '02-A03'].includes(currentStep.value.id)) return '任务没有成功启动。保留当前方案，再点击一次“开始配送”即可重试。'
    if (redCourseFailed.value && currentStep.value.id === 'WAIT-RED-VIOLATION') return '任务暂时未能恢复，系统正在重试原航线观察。'
    if (rewindFailed.value && currentStep.value.id === 'A08-RESTORE') return '回溯没有成功应用。黄色节点仍然保留，请再点击一次“回到这里重新选择”。'
    if (detourFailed.value && currentStep.value.id === 'A09-DETOUR') return '绕飞没有成功应用。查看系统提示后，再点击一次“从侧面绕飞”。'
    if (state.value.wrongAttempts >= 3 && isActionMode.value) return '先点亮起的目标。其他操作等教程结束后再试。'
    if (state.value.waiting && ['A03', '02-A01'].includes(currentStep.value.id)) return '正在生成与校验路线、配送点、奖励和候选基线…'
    if (state.value.waiting && ['A04', '02-A03'].includes(currentStep.value.id)) return '正在启动真实配送任务并暂停仿真…'
    if (state.value.waiting && currentStep.value.id === 'A08-RESTORE') return '正在撤销黄色节点之后的错误分支并恢复任务状态…'
    if (state.value.waiting && currentStep.value.id === 'A09-DETOUR') return '正在应用绕飞航线…'
    return currentStep.value?.text || ''
  })

  function normalizeServerStatus(status) {
    return ({ NEW: 'new', IN_PROGRESS: 'in_progress', COMPLETED: 'completed', SKIPPED: 'skipped' })[String(status || '').toUpperCase()] || 'new'
  }

  function groundTutorialItem() {
    return runtime.tutorialState.value?.items?.find(item => item.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER) || null
  }

  function chapterAccessGranted(chapterId, statusMap = statuses.value) {
    // Chapter 02 is server-authorized. Local progress can be stale after a
    // container restart, so it must never unlock this chapter by itself.
    if (chapterId === GROUND_COOP_TUTORIAL_CHAPTER) {
      return groundTutorialItem()?.availability === 'AVAILABLE'
    }
    return tutorialChapterUnlocked(chapterId, statusMap)
  }

  function groundTutorialEvidence() {
    const live = activeMission()?.groundRouting?.tutorialEvidence
    return live && typeof live === 'object' ? live : (groundTutorialItem()?.evidence || {})
  }

  function groundTutorialRunMatches() {
    return Boolean(activeRunId() && activeMission()?.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER
      && (!progressContext.value.runId || String(progressContext.value.runId) === activeRunId()))
  }

  function groundPaceDeparted() {
    const pace = activeMission()?.paceVehicle
    if (!pace) return false
    const status = String(pace.status || '').toUpperCase()
    return ['RUNNING', 'ARRIVED'].includes(status)
      || (status !== 'WAITING' && Number(pace.departureCountdownSeconds) <= 0)
  }

  function configureGroundTutorialCamera(stepId = currentStep.value?.id) {
    if (typeof runtime.setTutorialMapCameraTarget !== 'function') return
    const closeup = activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER
      && ['02-D03-PACE', '02-D03-DEADLINE'].includes(stepId)
    runtime.setTutorialMapCameraTarget(closeup ? 'pace' : '')
  }

  function dispatch(event) {
    state.value = tutorialReducer(state.value, event, steps.value)
  }

  function clearTransitionTimer() {
    if (transitionTimer) window.clearTimeout(transitionTimer)
    transitionTimer = null
  }

  function recordProgress(chapterId, status = 'in_progress', stepId) {
    const chapter = getTutorialChapter(chapterId)
    const context = [TUTORIAL_CHAPTER, GROUND_COOP_TUTORIAL_CHAPTER].includes(chapter.id) ? progressContext.value : {}
    const next = createProgressRecord(stepId || chapter.steps[0].id, status, Date.now(), chapter.id, context)
    window.localStorage.setItem(chapter.storageKey, JSON.stringify(next))
    progressRecords.value = { ...progressRecords.value, [chapter.id]: next }
    return next
  }

  function activeMission() {
    return runtime.context?.rawSnapshot?.value?.mission || runtime.context?.mission?.value?.source?.mission || null
  }

  function focusRedCourseDrone() {
    const droneId = String(runtime.context?.mission?.value?.actors
      ?.find(actor => actor.kind === 'UAV')?.id || '')
    return Boolean(droneId && runtime.context?.focusActor(droneId))
  }

  function restoreRedCourseOverview(stepId = currentStep.value?.id) {
    if (stepId !== 'D13-REWIND' || !runtime.context?.focusedActorId?.value) return false
    runtime.context.clearFocus()
    return true
  }

  function activeRunId() {
    return String(runtime.session.value?.id || '')
  }

  function activeTaskId() {
    return String(runtime.session.value?.taskInstanceId || activeMission()?.taskId || '')
  }

  function updateProgressContext(updates, persist = true) {
    progressContext.value = { ...progressContext.value, ...updates }
    if (persist && activeChapterId.value === TUTORIAL_CHAPTER && isActive.value) {
      recordProgress(TUTORIAL_CHAPTER, 'in_progress', currentStep.value.id)
    }
  }

  function captureTaskContext(source = runtime.taskPreview.value) {
    const plan = source?.plan || source || activeMission() || {}
    const taskId = String(source?.taskId || plan.taskId || activeTaskId() || '')
    const volumes = plan.airspace?.runtimeVolumes || plan.airspace?.volumes || []
    const redVolume = volumes.find(volume => volume.ruleType === 'ABSOLUTE_NO_FLY')
    const diamonds = Array.isArray(plan.rewardDiamonds) ? plan.rewardDiamonds : []
    const redDiamond = diamonds.find(diamond => (
      String(diamond.linkedVolumeId || '') === String(redVolume?.id || '') && diamond.requiredAction === 'DETOUR'
    ))
    updateProgressContext({
      ...(taskId ? { taskId } : {}),
      ...(redVolume?.id ? { redVolumeId: String(redVolume.id) } : {}),
      ...(redDiamond?.id ? { redDiamondId: String(redDiamond.id) } : {})
    })
    return progressContext.value
  }

  function tutorialRunMatches() {
    return Boolean(progressContext.value.runId && activeRunId()
      && String(progressContext.value.runId) === activeRunId()
      && (!progressContext.value.taskId || String(progressContext.value.taskId) === activeTaskId()))
  }

  function selectedRedAction() {
    const volumes = activeMission()?.airspace?.runtimeVolumes || activeMission()?.airspace?.volumes || []
    const red = volumes.find(volume => String(volume.id) === String(progressContext.value.redVolumeId || ''))
      || volumes.find(volume => volume.ruleType === 'ABSOLUTE_NO_FLY')
    return red?.selectedAction || null
  }

  function redInspectorOpen() {
    const redVolumeId = String(progressContext.value.redVolumeId || '')
    return Boolean(redVolumeId && String(runtime.selectedAirspaceId.value || '') === redVolumeId)
  }

  function ensureRedInspectorOpen(stepId = currentStep.value?.id) {
    if (!RED_INSPECTOR_REQUIRED_STEPS.has(String(stepId || '')) || redInspectorOpen()) return false
    const redVolumeId = String(progressContext.value.redVolumeId || '')
    if (!redVolumeId) return false
    return runtime.selectAirspace(redVolumeId) !== false
  }

  function redDiamondCollected() {
    const id = String(progressContext.value.redDiamondId || '')
    const collected = activeMission()?.economy?.collectedDiamondIds || []
    return Boolean(id && collected.map(String).includes(id))
  }

  const RED_CONFLICT_UNLOCKED_STEPS = new Set([
    'A09-CONFLICT', 'D14-INSPECT', 'A09-DETOUR', 'D15-DETOUR', 'WAIT-DIAMOND',
    'D16-REWARD', 'D17-BATTERY', 'D18-COMPLETE'
  ])

  function airspaceInspectorMustStayClosed(stepId = currentStep.value?.id) {
    const step = String(stepId || '')
    return !RED_CONFLICT_UNLOCKED_STEPS.has(step) || AIRSPACE_FREE_STEPS.has(step)
  }

  function configureProloguePresentationGuards(stepId = currentStep.value?.id) {
    runtime.setTutorialMissionReactionsSuppressed(true)
    const step = String(stepId || '')
    const conflictUnlocked = RED_CONFLICT_UNLOCKED_STEPS.has(step)
    runtime.setTutorialRedConflictLocked(!conflictUnlocked)
    if (airspaceInspectorMustStayClosed(step)) runtime.closeAirspace()
    else ensureRedInspectorOpen(step)
    // A07 itself unlocks only once its instruction is visible. Preserve that
    // selection through A07 success and the A08 transition so the real restore
    // button remains available, then lock the timeline again at D14.
    const rewindUnlocked = (
      step === 'A07-CHECKPOINT'
      && [TUTORIAL_PHASES.ACTION, TUTORIAL_PHASES.ACTION_SUCCESS].includes(phase.value)
    ) || (
      step === 'A08-RESTORE'
      && [TUTORIAL_PHASES.TRANSITION_TO_ACTION, TUTORIAL_PHASES.ACTION, TUTORIAL_PHASES.ACTION_SUCCESS].includes(phase.value)
    )
    runtime.setTutorialRewindLocked(!rewindUnlocked)
  }

  function redFineTransaction() {
    const redVolumeId = String(progressContext.value.redVolumeId || '')
    if (!redVolumeId) return null
    const transactions = activeMission()?.economy?.recentTransactions || []
    const current = [...transactions].reverse().find(transaction => (
      transaction.entryType === 'AIRSPACE_FINE'
      && String(transaction.referenceId || '') === redVolumeId
    ))
    const latest = runtime.latestEconomyTransaction?.value
    if (current) return current
    return latest?.entryType === 'AIRSPACE_FINE' && String(latest.referenceId || '') === redVolumeId
      ? latest
      : null
  }

  function latestRedCheckpoint() {
    const timeline = activeMission()?.timeline || {}
    const checkpoints = Array.isArray(timeline.checkpoints) ? timeline.checkpoints : []
    const redVolumeId = String(progressContext.value.redVolumeId || '')
    const latestId = String(timeline.latestCheckpointId || '')
    return checkpoints.find(checkpoint => (
      checkpoint.available
      && String(checkpoint.id || '') === latestId
      && String(checkpoint.volumeId || '') === redVolumeId
    )) || [...checkpoints].reverse().find(checkpoint => (
      checkpoint.available && String(checkpoint.volumeId || '') === redVolumeId
    )) || null
  }

  function restoredRedCheckpoint(record = progressContext.value) {
    const checkpoints = activeMission()?.timeline?.checkpoints || []
    const checkpointId = String(record?.rewindCheckpointId || '')
    const redVolumeId = String(record?.redVolumeId || '')
    return [...checkpoints].reverse().find(checkpoint => (
      String(checkpoint.status || '') === 'USED'
      && (
        (checkpointId && String(checkpoint.id || '') === checkpointId)
        || (redVolumeId && String(checkpoint.volumeId || '') === redVolumeId)
      )
    )) || null
  }

  function progressCheckpointRestored(record) {
    return Boolean(restoredRedCheckpoint(record))
  }

  function progressRedDetourApplied(record) {
    const redVolumeId = String(record?.redVolumeId || '')
    const volumes = activeMission()?.airspace?.runtimeVolumes || activeMission()?.airspace?.volumes || []
    const action = volumes.find(volume => String(volume.id || '') === redVolumeId)?.selectedAction
    return Boolean(action?.actionType === 'DETOUR' && action?.status === 'APPLIED')
  }

  function progressRedDiamondCollected(record) {
    const diamondId = String(record?.redDiamondId || '')
    const collected = activeMission()?.economy?.collectedDiamondIds || []
    return Boolean(diamondId && collected.map(String).includes(diamondId))
  }

  async function releaseTutorialPause() {
    if (!progressContext.value.pausedByTutorial) return false
    const matches = tutorialRunMatches()
    const shouldResume = matches
      && runtime.session.value?.status === 'RUNNING'
      && Number(runtime.timeScale.value) === 0
    if (shouldResume) {
      const resumed = await runtime.resumeMission(Number(progressContext.value.resumeTimeScale) || 1)
      if (!resumed) return false
    }
    updateProgressContext({ pausedByTutorial: false }, false)
    return true
  }

  function selectChapter(chapterId) {
    const card = chapterCards.value.find(chapter => chapter.id === chapterId)
    if (!card?.unlocked || isActive.value) return false
    selectedChapterId.value = chapterId
    return true
  }

  async function startTutorial(chapterId = selectedChapterId.value) {
    const card = chapterCards.value.find(chapter => chapter.id === chapterId)
    if (!card || !ready.value || !replayAllowed.value || chapterPreparing.value) return false
    if (chapterId !== GROUND_COOP_TUTORIAL_CHAPTER && !card.unlocked) return false

    chapterPreparing.value = true
    try {
      if (chapterId === GROUND_COOP_TUTORIAL_CHAPTER) {
        try {
          await runtime.refreshTutorialState()
        } catch {
          runtime.notice.value = '教程状态正在同步，请稍后再试。'
          return false
        }
        if (!chapterAccessGranted(chapterId)) {
          showLockedGroundTutorial()
          return false
        }
      }
      if (chapterId === TUTORIAL_CHAPTER) {
        await fleetRuntime.initialize({ force: true }).catch(() => null)
        const issue = tutorialFleetIssue(fleetRuntime.snapshot.value)
        if (issue) {
          await routeToFleetRecovery(issue)
          return false
        }
      } else if (chapterId === FLEET_TUTORIAL_CHAPTER) {
        if (!fleetRuntime.initialized.value) await fleetRuntime.initialize().catch(() => null)
      }

      clearTransitionTimer()
      runtime.closePlanner()
      fleetRuntime?.close()
      generationFailed.value = false
      startFailed.value = false
      detourFailed.value = false
      redCourseFailed.value = false
      rewindFailed.value = false
      progressContext.value = {}
      generationStarted = false
      startActionStarted = false
      detourActionStarted = false
      redCourseInFlight = false
      redCourseObservationStarted = false
      redFinePauseInFlight = false
      rewindActionStarted = false
      skipConfirmOpen.value = false
      skipError.value = ''
      if (chapterId === TUTORIAL_CHAPTER) {
        runtime.setTutorialGenerationPreset({ seed: PROLOGUE_TUTORIAL_SEED })
        configureProloguePresentationGuards(getTutorialChapter(chapterId).steps[0].id)
      } else if (chapterId === GROUND_COOP_TUTORIAL_CHAPTER) {
        runtime.clearTutorialRedConflictLocked()
        runtime.clearTutorialRewindLocked()
        runtime.setTutorialMissionReactionsSuppressed(true)
        runtime.startGroundTutorial()
      } else {
        runtime.clearTutorialGenerationPreset()
        runtime.clearTutorialRedConflictLocked()
        runtime.clearTutorialRewindLocked()
        runtime.clearTutorialMissionReactionsSuppressed()
      }
      activeChapterId.value = chapterId
      selectedChapterId.value = chapterId
      manualExpanded.value = false
      dispatch({ type: 'START' })
      recordProgress(chapterId, 'in_progress', getTutorialChapter(chapterId).steps[0].id)
      transitionTimer = window.setTimeout(
        () => dispatch({ type: 'INTRO_FINISHED' }),
        reducedMotion.value ? 80 : INTRO_DURATION
      )
      return true
    } finally {
      chapterPreparing.value = false
    }
  }

  function promptNextChapter(finishedChapterId) {
    if (finishedChapterId === TUTORIAL_CHAPTER) selectedChapterId.value = FLEET_TUTORIAL_CHAPTER
    else if (finishedChapterId === FLEET_TUTORIAL_CHAPTER) selectedChapterId.value = GROUND_COOP_TUTORIAL_CHAPTER
    manualExpanded.value = false
  }

  function closeActiveChapter({ promptNext = false } = {}) {
    const finishedChapterId = activeChapterId.value
    clearTransitionTimer()
    runtime.clearTutorialGenerationPreset()
    runtime.clearTutorialRedConflictLocked()
    runtime.clearTutorialRewindLocked()
    runtime.clearTutorialMissionReactionsSuppressed()
    runtime.setTutorialMapCameraTarget?.('')
    dispatch({ type: 'CLOSE' })
    activeChapterId.value = ''
    if (promptNext) promptNextChapter(finishedChapterId)
  }

  function showLockedGroundTutorial() {
    clearTransitionTimer()
    runtime.discardTaskPreview()
    runtime.closePlanner()
    runtime.clearTutorialGenerationPreset()
    runtime.clearTutorialRedConflictLocked()
    runtime.clearTutorialRewindLocked()
    runtime.clearTutorialMissionReactionsSuppressed()
    runtime.setTutorialMapCameraTarget?.('')
    if (activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER) {
      dispatch({ type: 'CLOSE' })
      activeChapterId.value = ''
    }
    selectedChapterId.value = GROUND_COOP_TUTORIAL_CHAPTER
    manualExpanded.value = true
    generationFailed.value = false
    startFailed.value = false
    runtime.notice.value = '教程 02 尚未解锁，请先完成第 01 章“认识你的车队”。'
  }

  async function routeToFleetRecovery(issue) {
    if (!issue || fleetRecoveryInFlight) return false
    fleetRecoveryInFlight = true
    try {
      if (activeChapterId.value === TUTORIAL_CHAPTER) closeActiveChapter()
      else {
        runtime.clearTutorialGenerationPreset()
        runtime.clearTutorialRedConflictLocked()
        runtime.clearTutorialRewindLocked()
        runtime.clearTutorialMissionReactionsSuppressed()
        manualExpanded.value = false
      }
      runtime.closePlanner()
      await fleetRuntime.open()
      fleetRuntime.selectFirstForCategory(issue.category)
      fleetRuntime.showGuidance({
        source: 'TUTORIAL_00',
        title: '教程 00 暂时无法开始',
        message: issue.message,
        action: issue.action
      })
      runtime.notice.value = `${issue.message} ${issue.action || ''}`.trim()
      return true
    } finally {
      fleetRecoveryInFlight = false
    }
  }

  async function recoverUnavailableFleet() {
    if (fleetRecoveryInFlight || activeChapterId.value !== TUTORIAL_CHAPTER) return false
    await fleetRuntime.initialize({ force: true }).catch(() => null)
    const issue = tutorialFleetIssue(fleetRuntime.snapshot.value)
    return issue ? routeToFleetRecovery(issue) : false
  }

  async function performSkipTutorial(chapterId) {
    const chapter = getTutorialChapter(chapterId)
    if (!chapterCards.value.find(item => item.id === chapter.id)?.unlocked) return
    const active = activeChapterId.value === chapter.id && isActive.value
    let resolvedStatus = 'skipped'
    if (chapter.id === GROUND_COOP_TUTORIAL_CHAPTER) {
      skipInFlight.value = true
      skipError.value = ''
      try {
        const runningGroundTutorial = groundTutorialRunMatches() && !runtime.isTerminal.value
        if (runningGroundTutorial && !(await runtime.endMission())) throw new Error(runtime.notice.value || '教学任务中止失败')
        if (!runningGroundTutorial) runtime.discardTaskPreview()
        runtime.closePlanner()
        const synced = await runtime.syncTutorialStatus(GROUND_COOP_TUTORIAL_CHAPTER, GROUND_COOP_TUTORIAL_VERSION, 'SKIPPED')
        const saved = synced?.items?.find(item => item.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER)
        resolvedStatus = normalizeServerStatus(saved?.status) === 'completed' ? 'completed' : 'skipped'
      } catch (error) {
        skipError.value = error?.message || '跳过状态同步失败，请重试。'
        return false
      } finally {
        skipInFlight.value = false
      }
    } else {
      try {
        const synced = await runtime.syncTutorialStatus(chapter.id, String(chapter.version), 'SKIPPED')
        const saved = synced?.items?.find(item => item.tutorialId === chapter.id)
        resolvedStatus = normalizeServerStatus(saved?.status) === 'completed' ? 'completed' : 'skipped'
      } catch (error) {
        runtime.notice.value = error?.message || '教程进度同步失败，请重试。'
        return false
      }
    }
    recordProgress(chapter.id, resolvedStatus, active ? currentStep.value.id : chapter.steps[0].id)
    if (active && chapter.id === TUTORIAL_CHAPTER) await releaseTutorialPause()
    if (chapter.id === TUTORIAL_CHAPTER) {
      runtime.clearTutorialGenerationPreset()
      runtime.clearTutorialRedConflictLocked()
      runtime.clearTutorialRewindLocked()
      runtime.clearTutorialMissionReactionsSuppressed()
    }
    if (active) closeActiveChapter({ promptNext: [TUTORIAL_CHAPTER, FLEET_TUTORIAL_CHAPTER].includes(chapter.id) })
    else if ([TUTORIAL_CHAPTER, FLEET_TUTORIAL_CHAPTER].includes(chapter.id)) promptNextChapter(chapter.id)
    else manualExpanded.value = false
    skipConfirmOpen.value = false
    return true
  }

  async function skipTutorial(chapterId = activeChapterId.value || selectedChapterId.value) {
    const chapter = getTutorialChapter(chapterId)
    if (chapter.id === GROUND_COOP_TUTORIAL_CHAPTER) {
      skipError.value = ''
      skipConfirmOpen.value = true
      return false
    }
    return performSkipTutorial(chapter.id)
  }

  function cancelSkipTutorial() {
    if (skipInFlight.value) return
    skipConfirmOpen.value = false
    skipError.value = ''
  }

  async function syncCompletedChapter(chapterId) {
    if (![TUTORIAL_CHAPTER, FLEET_TUTORIAL_CHAPTER].includes(chapterId)) return
    const chapter = getTutorialChapter(chapterId)
    try {
      await runtime.syncTutorialStatus(chapter.id, String(chapter.version), 'COMPLETED')
    } catch (error) {
      runtime.notice.value = error?.message || '教程完成状态将在下次刷新时重试同步。'
    }
  }

  function confirmSkipTutorial() {
    return performSkipTutorial(GROUND_COOP_TUTORIAL_CHAPTER)
  }

  function toggleManual() {
    if (isActive.value) return
    if (!manualExpanded.value && !manualAttentionSeen.value) {
      const record = createManualAttentionRecord()
      window.localStorage.setItem(TUTORIAL_ATTENTION_STORAGE_KEY, JSON.stringify(record))
      manualAttentionSeen.value = true
    }
    manualExpanded.value = !manualExpanded.value
    if (manualExpanded.value) void runtime.refreshTutorialState().catch(() => null)
  }

  function finishTypewriter() {
    if (typewriterTimer) window.clearInterval(typewriterTimer)
    typewriterTimer = null
    displayedText.value = effectiveText.value
    typingComplete.value = true
  }

  function startTypewriter() {
    if (typewriterTimer) window.clearInterval(typewriterTimer)
    typewriterTimer = null
    const characters = Array.from(effectiveText.value)
    if (reducedMotion.value || phase.value !== TUTORIAL_PHASES.DIALOGUE) {
      displayedText.value = effectiveText.value
      typingComplete.value = true
      return
    }
    displayedText.value = ''
    typingComplete.value = false
    let cursor = 0
    typewriterTimer = window.setInterval(() => {
      cursor += 1
      displayedText.value = characters.slice(0, cursor).join('')
      if (cursor >= characters.length) finishTypewriter()
    }, 30)
  }

  function advanceDialogue() {
    if (phase.value !== TUTORIAL_PHASES.DIALOGUE) return
    if (!typingComplete.value) {
      finishTypewriter()
      return
    }
    dispatch({ type: 'ADVANCE' })
    if (phase.value === TUTORIAL_PHASES.TRANSITION_TO_ACTION) {
      clearTransitionTimer()
      transitionTimer = window.setTimeout(
        () => dispatch({ type: 'ACTION_READY' }),
        reducedMotion.value ? 60 : ACTION_TRANSITION
      )
    }
  }

  function completeAction() {
    if (phase.value !== TUTORIAL_PHASES.ACTION) return
    dispatch({ type: 'ACTION_COMPLETED' })
    clearTransitionTimer()
    transitionTimer = window.setTimeout(() => {
      dispatch({ type: 'SUCCESS_FINISHED' })
      if (phase.value === TUTORIAL_PHASES.TRANSITION_TO_ACTION) {
        transitionTimer = window.setTimeout(
          () => dispatch({ type: 'ACTION_READY' }),
          reducedMotion.value ? 60 : ACTION_TRANSITION
        )
      } else if (phase.value === TUTORIAL_PHASES.TRANSITION_TO_DIALOGUE) {
        transitionTimer = window.setTimeout(
          () => dispatch({ type: 'DIALOGUE_READY' }),
          reducedMotion.value ? 60 : DIALOGUE_TRANSITION
        )
      }
    }, reducedMotion.value ? 100 : SUCCESS_RIPPLE)
  }

  function handleTargetAction(event) {
    if (currentStep.value.id === 'A01') {
      window.setTimeout(() => runtime.plannerOpen.value && completeAction(), 0)
      return
    }
    if (currentStep.value.id === 'A02') {
      window.setTimeout(() => {
        const checked = targetElement.value?.querySelector?.('input[type="radio"]')?.checked
        if (checked) completeAction()
      }, 0)
      return
    }
    if (currentStep.value.id === 'A03' && !runtime.busy.value && !state.value.waiting) {
      previewBeforeAction = runtime.taskPreview.value
      generationStarted = false
      generationFailed.value = false
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === 'A04' && !runtime.busy.value && !state.value.waiting) {
      captureTaskContext()
      startFailed.value = false
      startActionStarted = true
      updateProgressContext({ resumeTimeScale: Number(runtime.timeScale.value) || 1 })
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === 'A04-FOLLOW') {
      const deviceControl = event?.target instanceof Element
        ? event.target.closest('[data-device-id]')
        : null
      const deviceId = String(deviceControl?.dataset?.deviceId || '')
      if (!deviceId) {
        registerWrongInteraction()
        return
      }
      if (deviceId) runtime.context.focusActor(deviceId)
      window.setTimeout(() => {
        if (runtime.mapFollowingDeviceId.value) completeAction()
        else registerWrongInteraction()
      }, 0)
      return
    }
    if (currentStep.value.id === 'A04-OVERVIEW') {
      window.setTimeout(() => {
        if (!runtime.mapFollowingDeviceId.value) completeAction()
      }, 0)
      return
    }
    if (currentStep.value.id === 'A07-CHECKPOINT') {
      completeCheckpointPreview(event)
      return
    }
    if (currentStep.value.id === 'A08-RESTORE' && !runtime.rewindBusy.value && !state.value.waiting) {
      rewindFailed.value = false
      rewindActionStarted = true
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === 'A09-CONFLICT') {
      window.setTimeout(() => {
        if (redInspectorOpen()) completeAction()
        else registerWrongInteraction()
      }, 0)
      return
    }
    if (currentStep.value.id === 'A09-DETOUR' && !runtime.airspaceActionBusy.value && !state.value.waiting) {
      detourFailed.value = false
      detourActionStarted = true
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === 'F01-A01') {
      window.setTimeout(() => fleetRuntime.isOpen.value && completeAction(), 0)
      return
    }
    if (currentStep.value.id === '02-A01' && !runtime.busy.value && !state.value.waiting) {
      previewBeforeAction = runtime.taskPreview.value
      generationStarted = false
      generationFailed.value = false
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === '02-A02') {
      window.setTimeout(evaluateCurrentCondition, 0)
      return
    }
    if (currentStep.value.id === '02-A03' && !runtime.busy.value && !state.value.waiting) {
      startFailed.value = false
      startActionStarted = true
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (['02-A04', '02-A05'].includes(currentStep.value.id)) {
      window.setTimeout(evaluateCurrentCondition, 450)
    }
  }

  function registerWrongInteraction() {
    wrongPulseKey.value += 1
    dispatch({ type: 'WRONG_INTERACTION' })
  }

  function eventHitsTarget(event) {
    const target = targetElement.value
    return Boolean(target && (
      event.composedPath?.().includes(target) ||
      target === event.target ||
      target.contains(event.target)
    ))
  }

  function eventHitsAllowedSelector(event) {
    const selector = currentStep.value?.allowSelector
    if (!selector || !(event.target instanceof Element)) return false
    return Boolean(event.target.closest(selector))
  }

  function eventHitsActionTarget(event) {
    return currentStep.value?.allowSelector ? eventHitsAllowedSelector(event) : eventHitsTarget(event)
  }

  function eventHitsTutorialControl(event) {
    return Boolean(event.composedPath?.().some(element => element?.dataset?.tutorialControl !== undefined))
  }

  function onDocumentClick(event) {
    if (!locksInteraction.value || eventHitsTutorialControl(event)) return
    if (eventHitsActionTarget(event)) {
      handleTargetAction(event)
      return
    }
    event.preventDefault()
    event.stopImmediatePropagation()
    registerWrongInteraction()
  }

  function onDocumentPointerDown(event) {
    if (locksInteraction.value && !eventHitsTutorialControl(event) && !eventHitsActionTarget(event)) {
      event.preventDefault()
      event.stopImmediatePropagation()
    }
  }

  function onDocumentKeydown(event) {
    if (!['Enter', ' ', 'Spacebar'].includes(event.key)) return
    if (phase.value === TUTORIAL_PHASES.DIALOGUE) {
      if (
        event.target instanceof HTMLInputElement ||
        event.target instanceof HTMLSelectElement ||
        event.target instanceof HTMLTextAreaElement ||
        (event.target instanceof Element && event.target.closest('.tutorial-dialogue'))
      ) return
      event.preventDefault()
      advanceDialogue()
      return
    }
    if (
      locksInteraction.value &&
      !eventHitsTutorialControl(event) &&
      !eventHitsActionTarget(event) &&
      !targetElement.value?.contains(document.activeElement)
    ) {
      event.preventDefault()
      event.stopImmediatePropagation()
      registerWrongInteraction()
    }
  }

  function updateTargetRect() {
    const element = targetElement.value
    if (!element?.isConnected) {
      refreshTarget()
      return
    }
    const rect = element.getBoundingClientRect()
    targetRect.value = {
      left: rect.left,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      width: rect.width,
      height: rect.height
    }
  }

  function setTarget(element) {
    if (targetElement.value === element) {
      if (element) updateTargetRect()
      return
    }
    targetElement.value?.classList.remove('tutorial-target-active')
    targetResizeObserver?.disconnect()
    targetResizeObserver = null
    targetElement.value = element || null
    targetRect.value = null
    if (!element) return
    targetSyncDelayed.value = false
    element.classList.add('tutorial-target-active')
    targetResizeObserver = new ResizeObserver(updateTargetRect)
    targetResizeObserver.observe(element)
    updateTargetRect()
    if (phase.value === TUTORIAL_PHASES.ACTION) nextTick(() => element.focus?.({ preventScroll: true }))
  }

  function refreshTarget() {
    if (!tracksTarget.value) {
      setTarget(null)
      return
    }
    if (['D14-RETRY', 'A09-CONFLICT'].includes(currentStep.value.id) && redInspectorOpen()) {
      setTarget(null)
      return
    }
    setTarget(document.querySelector(`[data-tutorial-id="${currentStep.value.targetId}"]`))
    if (currentStep.value.id === '02-A04' && document.querySelector('[data-tutorial-ground-reward]')) {
      targetSyncDelayed.value = false
    }
  }

  function observeTarget() {
    targetMutationObserver?.disconnect()
    if (targetDelayTimer) window.clearTimeout(targetDelayTimer)
    targetDelayTimer = null
    targetSyncDelayed.value = false
    setTarget(null)
    if (!tracksTarget.value) return
    refreshTarget()
    targetMutationObserver = new MutationObserver(refreshTarget)
    targetMutationObserver.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'style', 'disabled', 'data-tutorial-id']
    })
    targetDelayTimer = window.setTimeout(() => {
      if (normalizeTimelineTutorialState()) return
      refreshTarget()
      if (!targetElement.value && currentStep.value.id === 'A09-CONFLICT'
        && phase.value === TUTORIAL_PHASES.ACTION && tutorialRunMatches()) {
        const redVolumeId = String(progressContext.value.redVolumeId || '')
        if (redVolumeId && runtime.selectAirspace(redVolumeId) !== false) {
          nextTick(evaluateCurrentCondition)
          return
        }
      }
      if (!targetElement.value || (currentStep.value.id === '02-A04' && !document.querySelector('[data-tutorial-ground-reward]'))) {
        targetSyncDelayed.value = true
      }
    }, TARGET_DELAY_NOTICE)
  }

  function resumeChapter(chapterId, stepId, record = null) {
    activeChapterId.value = chapterId
    selectedChapterId.value = chapterId
    manualExpanded.value = false
    progressContext.value = chapterId === TUTORIAL_CHAPTER
      ? Object.fromEntries(['taskId', 'runId', 'redVolumeId', 'redDiamondId', 'rewindCheckpointId', 'redFineTransactionId', 'pausedByTutorial', 'resumeTimeScale']
        .filter(key => record?.[key] !== undefined)
        .map(key => [key, record[key]]))
      : chapterId === GROUND_COOP_TUTORIAL_CHAPTER
        ? Object.fromEntries(['taskId', 'runId'].filter(key => record?.[key]).map(key => [key, record[key]]))
        : {}
    if (chapterId === TUTORIAL_CHAPTER && !record?.runId) {
      runtime.setTutorialGenerationPreset({ seed: PROLOGUE_TUTORIAL_SEED })
    }
    if (chapterId === TUTORIAL_CHAPTER) configureProloguePresentationGuards(stepId)
    else if (chapterId === GROUND_COOP_TUTORIAL_CHAPTER) {
      runtime.clearTutorialRedConflictLocked()
      runtime.clearTutorialRewindLocked()
      runtime.setTutorialMissionReactionsSuppressed(true)
    }
    else {
      runtime.clearTutorialRedConflictLocked()
      runtime.clearTutorialRewindLocked()
      runtime.clearTutorialMissionReactionsSuppressed()
    }
    if (chapterId === TUTORIAL_CHAPTER && record?.redVolumeId && AIRSPACE_PANEL_DEPENDENT_STEPS.has(stepId)) {
      runtime.selectAirspace(record.redVolumeId)
    }
    dispatch({ type: 'RESUME', stepId })
    recordProgress(chapterId, 'in_progress', stepId)
  }

  function rewindToAction(stepId) {
    dispatch({ type: 'RESUME', stepId })
    recordProgress(activeChapterId.value, 'in_progress', stepId)
  }

  function hydrateProgress() {
    if (initialized || !runtime.initialized.value) return
    initialized = true
    manualAttentionSeen.value = Boolean(parseManualAttentionRecord(
      window.localStorage.getItem(TUTORIAL_ATTENTION_STORAGE_KEY)
    ))
    const records = Object.fromEntries(TUTORIAL_CHAPTERS.map(chapter => {
      const local = parseProgressRecord(window.localStorage.getItem(chapter.storageKey), chapter.id)
      const server = runtime.tutorialState.value?.items?.find(item => item.tutorialId === chapter.id)
      const serverStatus = normalizeServerStatus(server?.status)
      if (!server || String(server.tutorialVersion) !== String(chapter.version) || serverStatus === 'new') return [chapter.id, local]
      return [chapter.id, {
        ...(local || createProgressRecord(chapter.steps[0].id, serverStatus, Date.now(), chapter.id)),
        status: serverStatus,
        ...(server.taskId ? { taskId: String(server.taskId) } : {}),
        ...(server.runId ? { runId: String(server.runId) } : {})
      }]
    }))
    progressRecords.value = records

    const restoredStatuses = Object.fromEntries(TUTORIAL_CHAPTERS.map(chapter => [
      chapter.id,
      records[chapter.id]?.status || 'new'
    ]))
    const resumable = TUTORIAL_CHAPTERS.find(chapter => {
      if (!chapterAccessGranted(chapter.id, restoredStatuses)) return false
      if (!canAutoResumeTutorial(records[chapter.id], {
        activeRunId: activeRunId(),
        isTerminal: runtime.isTerminal.value
      })) return false
      return Boolean(resolveResumeStep(records[chapter.id], {
        plannerOpen: runtime.plannerOpen.value,
        fleetHubOpen: fleetRuntime?.isOpen.value,
        chapterId: chapter.id,
        activeRunId: activeRunId(),
        rewindCheckpointRestored: progressCheckpointRestored(records[chapter.id]),
        redDetourApplied: progressRedDetourApplied(records[chapter.id]),
        redDiamondCollected: progressRedDiamondCollected(records[chapter.id])
      }))
    })
    if (resumable && canReplayTutorial({
      hasSession: runtime.hasSession.value,
      isTerminal: runtime.isTerminal.value,
      requestedRunId: records[resumable.id]?.runId,
      activeRunId: activeRunId()
    })) {
      const stepId = resolveResumeStep(records[resumable.id], {
        plannerOpen: runtime.plannerOpen.value,
        fleetHubOpen: fleetRuntime?.isOpen.value,
        chapterId: resumable.id,
        activeRunId: activeRunId(),
        rewindCheckpointRestored: progressCheckpointRestored(records[resumable.id]),
        redDetourApplied: progressRedDetourApplied(records[resumable.id]),
        redDiamondCollected: progressRedDiamondCollected(records[resumable.id]),
        groundEvidence: groundTutorialEvidence()
      })
      resumeChapter(resumable.id, stepId, records[resumable.id])
      return
    }

    const next = TUTORIAL_CHAPTERS.find(chapter => (
      chapterAccessGranted(chapter.id, restoredStatuses) && !records[chapter.id]
    ))
    const fallback = TUTORIAL_CHAPTERS.find(chapter => (
      chapterAccessGranted(chapter.id, restoredStatuses) && records[chapter.id]
    )) || TUTORIAL_CHAPTERS.find(chapter => chapterAccessGranted(chapter.id, restoredStatuses))
    selectedChapterId.value = (next || resumable || fallback || TUTORIAL_CHAPTERS[0]).id
    // Announce new chapters with the compact beacon. Opening the manual is an
    // explicit user action, so refresh never covers the map by itself.
    manualExpanded.value = false
  }

  function onMotionPreferenceChange(event) {
    reducedMotion.value = event.matches
    if (event.matches) finishTypewriter()
  }

  async function ensureStartedTaskPaused() {
    if (pauseInFlight || activeChapterId.value !== TUTORIAL_CHAPTER
      || currentStep.value.id !== 'A04' || phase.value !== TUTORIAL_PHASES.ACTION
      || runtime.busy.value || runtime.session.value?.status !== 'RUNNING') return
    const expectedTaskId = String(progressContext.value.taskId || '')
    if (!expectedTaskId || expectedTaskId !== activeTaskId()) return

    pauseInFlight = true
    try {
      captureTaskContext(activeMission())
      updateProgressContext({ runId: activeRunId() })
      const alreadyOwned = progressContext.value.pausedByTutorial === true
      const wasMoving = Number(runtime.timeScale.value) > 0
      const paused = alreadyOwned && Number(runtime.timeScale.value) === 0
        ? true
        : await runtime.pauseMission()
      if (!paused) {
        startFailed.value = true
        dispatch({ type: 'ACTION_RETRY' })
        return
      }
      updateProgressContext({
        pausedByTutorial: alreadyOwned || wasMoving,
        resumeTimeScale: Number(progressContext.value.resumeTimeScale) || Number(runtime.lastActiveTimeScale.value) || 1
      })
      runtime.clearTutorialGenerationPreset()
      completeAction()
    } finally {
      pauseInFlight = false
    }
  }

  async function ensureGroundTaskStartedPaused() {
    if (pauseInFlight || activeChapterId.value !== GROUND_COOP_TUTORIAL_CHAPTER
      || currentStep.value.id !== '02-A03' || phase.value !== TUTORIAL_PHASES.ACTION
      || runtime.busy.value || runtime.session.value?.status !== 'RUNNING'
      || activeMission()?.tutorialId !== GROUND_COOP_TUTORIAL_CHAPTER) return
    pauseInFlight = true
    try {
      const paused = Number(runtime.timeScale.value) === 0 || await runtime.pauseMission()
      if (!paused) {
        startFailed.value = true
        dispatch({ type: 'ACTION_RETRY' })
        return
      }
      progressContext.value = { taskId: activeTaskId(), runId: activeRunId() }
      recordProgress(GROUND_COOP_TUTORIAL_CHAPTER, 'in_progress', currentStep.value.id)
      runtime.clearTutorialGenerationPreset()
      await runtime.refreshTutorialState().catch(() => null)
      completeAction()
    } finally {
      pauseInFlight = false
    }
  }

  async function runGroundObservationStep() {
    if (groundMilestoneInFlight || activeChapterId.value !== GROUND_COOP_TUTORIAL_CHAPTER
      || phase.value !== TUTORIAL_PHASES.ACTION || !['02-W00-PACE', '02-W01', '02-W02', '02-W03'].includes(currentStep.value.id)
      || !groundTutorialRunMatches()) return
    const stepId = currentStep.value.id
    const evidenceKey = { '02-W01': 'uavTakeoff', '02-W02': 'uavRecovered', '02-W03': 'missionCompleted' }[stepId]
    const evidence = groundTutorialEvidence()
    const milestoneReached = stepId === '02-W00-PACE' ? groundPaceDeparted() : Boolean(evidence[evidenceKey])
    groundMilestoneInFlight = true
    try {
      if (!milestoneReached && runtime.session.value?.status === 'RUNNING' && Number(runtime.timeScale.value) !== 5) {
        await runtime.resumeMission(5)
        return
      }
      if (!milestoneReached) return
      if (stepId !== '02-W03' && runtime.session.value?.status === 'RUNNING' && Number(runtime.timeScale.value) > 0) {
        if (!(await runtime.pauseMission())) return
      }
      if (stepId === '02-W03') {
        await runtime.refreshTutorialState().catch(() => null)
        if (normalizeServerStatus(groundTutorialItem()?.status) !== 'completed') return
      }
      if (currentStep.value.id === stepId && phase.value === TUTORIAL_PHASES.ACTION) completeAction()
    } finally {
      groundMilestoneInFlight = false
    }
  }

  async function beginRedCourseObservation() {
    if (redCourseInFlight || redCourseObservationStarted || currentStep.value.id !== 'WAIT-RED-VIOLATION'
      || phase.value !== TUTORIAL_PHASES.ACTION || !tutorialRunMatches()) return
    if (redFineTransaction() && latestRedCheckpoint()) return
    redCourseInFlight = true
    redCourseObservationStarted = true
    redCourseFailed.value = false
    try {
      if (runtime.context?.timeMode.value === 'REPLAY') runtime.context.returnToLive()
      focusRedCourseDrone()
      const released = progressContext.value.pausedByTutorial
        ? await releaseTutorialPause()
        : (Number(runtime.timeScale.value) > 0
            || await runtime.resumeMission(Number(progressContext.value.resumeTimeScale) || Number(runtime.lastActiveTimeScale.value) || 1))
      if (!released) {
        redCourseFailed.value = true
        redCourseObservationStarted = false
        window.setTimeout(evaluateCurrentCondition, 1200)
        return
      }
    } finally {
      redCourseInFlight = false
    }
  }

  async function completeRedViolationWait() {
    if (redFinePauseInFlight || currentStep.value.id !== 'WAIT-RED-VIOLATION'
      || phase.value !== TUTORIAL_PHASES.ACTION || !tutorialRunMatches()) return
    const fine = redFineTransaction()
    const checkpoint = latestRedCheckpoint()
    if (!fine || !checkpoint) return

    redFinePauseInFlight = true
    try {
      const alreadyOwned = progressContext.value.pausedByTutorial === true && Number(runtime.timeScale.value) === 0
      const wasMoving = runtime.session.value?.status === 'RUNNING' && Number(runtime.timeScale.value) > 0
      const resumeTimeScale = Number(runtime.timeScale.value) || Number(runtime.lastActiveTimeScale.value) || Number(progressContext.value.resumeTimeScale) || 1
      if (wasMoving && !(await runtime.pauseMission())) return
      updateProgressContext({
        rewindCheckpointId: String(checkpoint.id || ''),
        redFineTransactionId: String(fine.id || fine.entryKey || ''),
        pausedByTutorial: alreadyOwned || wasMoving,
        resumeTimeScale
      })
      dispatch({ type: 'ACTION_WAIT' })
      await new Promise(resolve => window.setTimeout(resolve, RED_FINE_REACTION_DURATION))
      if (currentStep.value.id === 'WAIT-RED-VIOLATION' && phase.value === TUTORIAL_PHASES.ACTION) completeAction()
    } finally {
      redFinePauseInFlight = false
    }
  }

  function completeCheckpointPreview(event) {
    const checkpointElement = event?.target instanceof Element
      ? event.target.closest('[data-checkpoint-id]')
      : null
    const checkpointId = String(checkpointElement?.dataset?.checkpointId || '')
    const volumeId = String(checkpointElement?.dataset?.checkpointVolumeId || '')
    const checkpoint = latestRedCheckpoint()
    if (!checkpointId || checkpointId !== String(checkpoint?.id || '')
      || volumeId !== String(progressContext.value.redVolumeId || '')) {
      registerWrongInteraction()
      return
    }
    updateProgressContext({ rewindCheckpointId: checkpointId })
    window.setTimeout(() => {
      if (runtime.context?.timeMode.value === 'REPLAY') completeAction()
      else registerWrongInteraction()
    }, 0)
  }

  function recoverPrematureCheckpointRestore() {
    if (activeChapterId.value !== TUTORIAL_CHAPTER
      || !PREMATURE_REWIND_RECOVERY_STEPS.has(currentStep.value.id)
      || !tutorialRunMatches()) return false
    const checkpoint = restoredRedCheckpoint()
    if (!checkpoint) return false
    clearTransitionTimer()
    updateProgressContext({ rewindCheckpointId: String(checkpoint.id || progressContext.value.rewindCheckpointId || '') }, false)
    runtime.clearTutorialRedConflictLocked()
    runtime.setTutorialRewindLocked(true)
    runtime.context.returnToLive()
    runtime.closeAirspace()
    dispatch({ type: 'RESUME', stepId: 'D14-RETRY' })
    recordProgress(TUTORIAL_CHAPTER, 'in_progress', 'D14-RETRY')
    return true
  }

  function completeAlreadyPreviewedCheckpoint() {
    if (currentStep.value.id !== 'A07-CHECKPOINT' || phase.value !== TUTORIAL_PHASES.ACTION) return false
    const checkpoint = latestRedCheckpoint()
    if (!checkpoint || runtime.context?.timeMode.value !== 'REPLAY') return false
    const cursor = Number(runtime.context?.timeCursor.value || 0)
    if (Math.abs(cursor - Number(checkpoint.progress || 0)) > 0.35) return false
    updateProgressContext({ rewindCheckpointId: String(checkpoint.id || '') })
    completeAction()
    return true
  }

  function normalizeTimelineTutorialState() {
    if (recoverPrematureCheckpointRestore()) return true
    if (completeAlreadyPreviewedCheckpoint()) return true
    if (currentStep.value.id === 'WAIT-RED-VIOLATION' && runtime.context?.timeMode.value === 'REPLAY') {
      runtime.context.returnToLive()
      nextTick(evaluateCurrentCondition)
      return true
    }
    return false
  }

  function completeRewindAction(ack = runtime.lastRewindAck?.value) {
    if (currentStep.value.id !== 'A08-RESTORE' || phase.value !== TUTORIAL_PHASES.ACTION
      || !tutorialRunMatches() || !ack?.rewindId) return false
    if (String(ack.id || '') !== String(progressContext.value.rewindCheckpointId || '')
      || String(ack.volumeId || '') !== String(progressContext.value.redVolumeId || '')) return false
    rewindFailed.value = false
    // The rewind response selects its associated red volume. Keep the inspector
    // closed and the conflict entry hidden until A09-CONFLICT is actually ready,
    // otherwise an eager click (or the response selection itself) can skip the
    // required card interaction and strand target tracking on an open panel.
    runtime.setTutorialRedConflictLocked(true)
    runtime.closeAirspace()
    completeAction()
    return true
  }

  async function completeDetourAction(action = selectedRedAction()) {
    if (detourCompletionInFlight || currentStep.value.id !== 'A09-DETOUR'
      || phase.value !== TUTORIAL_PHASES.ACTION || !tutorialRunMatches()) return
    if (String(action?.volumeId || '') !== String(progressContext.value.redVolumeId || '')
      || String(action?.runId || '') !== activeRunId()
      || action?.actionType !== 'DETOUR' || action?.status !== 'APPLIED') return
    detourCompletionInFlight = true
    try {
      const released = progressContext.value.pausedByTutorial ? await releaseTutorialPause() : true
      if (!released) {
        detourFailed.value = true
        dispatch({ type: 'ACTION_RETRY' })
        return
      }
      completeAction()
    } finally {
      detourCompletionInFlight = false
    }
  }

  function evaluateCurrentCondition() {
    if (phase.value !== TUTORIAL_PHASES.ACTION) return
    if (activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER) {
      const evidence = groundTutorialEvidence()
      if (currentStep.value.id === '02-A02' && runtime.selectedBaselineRouteCandidateId.value) completeAction()
      else if (currentStep.value.id === '02-A03') void ensureGroundTaskStartedPaused()
      else if (currentStep.value.id === '02-A04' && evidence.routeOutsideReward) completeAction()
      else if (currentStep.value.id === '02-A05' && evidence.returnedToBaseline) completeAction()
      else if (['02-W00-PACE', '02-W01', '02-W02', '02-W03'].includes(currentStep.value.id)) void runGroundObservationStep()
      return
    }
    if (activeChapterId.value !== TUTORIAL_CHAPTER) return
    if (normalizeTimelineTutorialState()) return
    if (currentStep.value.id === 'A02') {
      const checked = document.querySelector('[data-tutorial-id="mission-zone-count-four"] input[type="radio"]')?.checked
      if (checked) completeAction()
    } else if (currentStep.value.id === 'A04') {
      void ensureStartedTaskPaused()
    } else if (currentStep.value.id === 'A04-FOLLOW') {
      if (runtime.mapFollowingDeviceId.value) completeAction()
    } else if (currentStep.value.id === 'A04-OVERVIEW') {
      if (!runtime.mapFollowingDeviceId.value) completeAction()
    } else if (currentStep.value.id === 'WAIT-RED-VIOLATION') {
      if (redFineTransaction() && latestRedCheckpoint()) void completeRedViolationWait()
      else void beginRedCourseObservation()
    } else if (currentStep.value.id === 'A08-RESTORE') {
      completeRewindAction()
    } else if (currentStep.value.id === 'A09-CONFLICT') {
      if (redInspectorOpen()) completeAction()
    } else if (currentStep.value.id === 'A09-DETOUR') {
      void completeDetourAction()
    } else if (currentStep.value.id === 'WAIT-DIAMOND' && redDiamondCollected()) {
      completeAction()
    }
  }

  watch(() => runtime.initialized.value, hydrateProgress, { immediate: true })
  watch(() => groundTutorialItem()?.availability, availability => {
    if (availability === 'AVAILABLE') return
    const groundPresetActive = runtime.tutorialGenerationPreset.value?.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER
    if (activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER || groundPresetActive) {
      showLockedGroundTutorial()
    }
  }, { flush: 'post' })
  watch(() => [activeChapterId.value, currentStep.value.id, phase.value], () => {
    if (activeChapterId.value === TUTORIAL_CHAPTER && isActive.value) {
      configureProloguePresentationGuards(currentStep.value.id)
      restoreRedCourseOverview(currentStep.value.id)
    }
    configureGroundTutorialCamera(currentStep.value.id)
    if (isActive.value && ![TUTORIAL_PHASES.INTRO, TUTORIAL_PHASES.COMPLETE].includes(phase.value)) {
      recordProgress(activeChapterId.value, 'in_progress', currentStep.value.id)
    }
    observeTarget()
    startTypewriter()
    nextTick(() => {
      if (!normalizeTimelineTutorialState()) evaluateCurrentCondition()
    })
    if (phase.value === TUTORIAL_PHASES.COMPLETE && activeChapterId.value) {
      const chapterId = activeChapterId.value
      if (chapterId === TUTORIAL_CHAPTER) void releaseTutorialPause()
      recordProgress(chapterId, 'completed', steps.value.at(-1).id)
      void syncCompletedChapter(chapterId)
      manualExpanded.value = false
      clearTransitionTimer()
      transitionTimer = window.setTimeout(
        () => closeActiveChapter({ promptNext: [TUTORIAL_CHAPTER, FLEET_TUTORIAL_CHAPTER].includes(chapterId) }),
        reducedMotion.value ? 500 : COMPLETE_DURATION
      )
    }
  })
  watch(() => runtime.plannerOpen.value, open => {
    if (open && phase.value === TUTORIAL_PHASES.ACTION && currentStep.value.id === 'A01') completeAction()
    if (!open && ((activeChapterId.value === TUTORIAL_CHAPTER && PLANNER_DEPENDENT_STEPS.has(currentStep.value.id))
      || (activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER && GROUND_COOP_PLANNER_STEPS.has(currentStep.value.id)))) window.setTimeout(() => {
        if (runtime.plannerOpen.value) return
        if (activeChapterId.value === TUTORIAL_CHAPTER) {
          if (currentStep.value.id === 'A04' && activeTaskId() === String(progressContext.value.taskId || '')) {
            void ensureStartedTaskPaused()
            return
          }
          rewindToAction('A01')
        } else if (activeChapterId.value === GROUND_COOP_TUTORIAL_CHAPTER) {
          if (currentStep.value.id === '02-A03' && activeMission()?.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER) {
            void ensureGroundTaskStartedPaused()
            return
          }
          runtime.startGroundTutorial()
          rewindToAction('02-A01')
        }
      }, 0)
  })
  watch(() => fleetRuntime?.isOpen.value, open => {
    if (open && phase.value === TUTORIAL_PHASES.ACTION && currentStep.value.id === 'F01-A01') completeAction()
    if (!open && activeChapterId.value === FLEET_TUTORIAL_CHAPTER && FLEET_HUB_DEPENDENT_STEPS.has(currentStep.value.id)) {
      rewindToAction('F01-A01')
    }
  })
  watch([() => runtime.busy.value, () => runtime.taskPreview.value], ([busy, preview]) => {
    if (!['A03', '02-A01'].includes(currentStep.value.id) || phase.value !== TUTORIAL_PHASES.ACTION || !state.value.waiting) return
    if (busy) {
      generationStarted = true
      return
    }
    if (!generationStarted) return
    if (preview && preview !== previewBeforeAction) {
      if (currentStep.value.id === '02-A01') {
        if (preview?.plan?.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER) completeAction()
        else {
          generationFailed.value = true
          dispatch({ type: 'ACTION_RETRY' })
        }
      } else {
        captureTaskContext(preview)
        const issue = tutorialFleetIssue(fleetRuntime.snapshot.value, preview)
        if (issue) void routeToFleetRecovery(issue)
        else completeAction()
      }
    }
    else {
      generationFailed.value = true
      dispatch({ type: 'ACTION_RETRY' })
      void recoverUnavailableFleet()
    }
  }, { flush: 'post' })
  watch([
    () => runtime.busy.value,
    () => runtime.session.value?.id,
    () => runtime.session.value?.taskInstanceId,
    () => runtime.session.value?.status
  ], ([busy]) => {
    if (!['A04', '02-A03'].includes(currentStep.value.id) || phase.value !== TUTORIAL_PHASES.ACTION || !state.value.waiting) return
    if (busy) return
    if (currentStep.value.id === '02-A03') {
      if (activeMission()?.tutorialId === GROUND_COOP_TUTORIAL_CHAPTER && runtime.session.value?.status === 'RUNNING') {
        void ensureGroundTaskStartedPaused()
      } else if (startActionStarted) {
        startFailed.value = true
        startActionStarted = false
        dispatch({ type: 'ACTION_RETRY' })
      }
      return
    }
    if (activeTaskId() === String(progressContext.value.taskId || '') && runtime.session.value?.status === 'RUNNING') {
      void ensureStartedTaskPaused()
    } else if (startActionStarted) {
      startFailed.value = true
      startActionStarted = false
      dispatch({ type: 'ACTION_RETRY' })
    }
  }, { flush: 'post' })
  watch(() => runtime.selectedAirspaceId.value, () => {
    if (activeChapterId.value === TUTORIAL_CHAPTER && isActive.value) {
      // Rewind acknowledgement can select the associated volume after the
      // restore action has already closed it. Reject that late selection until
      // A09-CONFLICT is visible so the player still performs the taught click.
      if (airspaceInspectorMustStayClosed() && runtime.selectedAirspaceId.value) {
        runtime.closeAirspace()
        return
      }
      ensureRedInspectorOpen()
    }
    evaluateCurrentCondition()
  }, { flush: 'post' })
  watch(() => runtime.mapFollowingDeviceId.value, evaluateCurrentCondition, { flush: 'post' })
  watch([
    () => runtime.latestEconomyTransaction?.value?.id,
    () => activeMission()?.economy?.recentTransactions?.length,
    () => activeMission()?.timeline?.latestCheckpointId,
    () => (activeMission()?.timeline?.checkpoints || []).map(checkpoint => `${checkpoint.id}:${checkpoint.status}`).join('|'),
    () => runtime.context?.timeMode.value,
    () => runtime.context?.timeCursor.value,
    () => runtime.timeScale.value
  ], evaluateCurrentCondition, { flush: 'post' })
  watch([
    () => runtime.rewindBusy.value,
    () => runtime.lastRewindAck?.value?.rewindId,
    () => runtime.rewindError.value
  ], ([busy]) => {
    if (currentStep.value.id !== 'A08-RESTORE' || phase.value !== TUTORIAL_PHASES.ACTION || !state.value.waiting) return
    if (busy) {
      rewindActionStarted = true
      return
    }
    if (!rewindActionStarted) return
    if (completeRewindAction()) {
      rewindActionStarted = false
      return
    }
    if (runtime.rewindError.value) {
      rewindFailed.value = true
      rewindActionStarted = false
      dispatch({ type: 'ACTION_RETRY' })
    }
  }, { flush: 'post' })
  watch([
    () => runtime.airspaceActionBusy.value,
    () => runtime.lastAirspaceAction?.value?.id,
    () => selectedRedAction()?.id
  ], ([busy]) => {
    if (currentStep.value.id !== 'A09-DETOUR' || phase.value !== TUTORIAL_PHASES.ACTION) return
    if (busy) return
    const action = runtime.lastAirspaceAction?.value || selectedRedAction()
    if (action?.actionType === 'DETOUR' && action?.status === 'APPLIED') {
      void completeDetourAction(action)
    } else if (detourActionStarted) {
      detourFailed.value = true
      detourActionStarted = false
      dispatch({ type: 'ACTION_RETRY' })
    }
  }, { flush: 'post' })
  watch(() => (activeMission()?.economy?.collectedDiamondIds || []).map(String).join('|'), evaluateCurrentCondition)
  watch([
    () => JSON.stringify(activeMission()?.groundRouting?.tutorialEvidence || {}),
    () => activeMission()?.groundRouting?.activeTemporaryTarget?.targetId,
    () => activeMission()?.paceVehicle?.status,
    () => Math.ceil(Number(activeMission()?.paceVehicle?.departureCountdownSeconds || 0)),
    () => runtime.selectedBaselineRouteCandidateId.value,
    () => groundTutorialItem()?.status,
    () => runtime.session.value?.status
  ], evaluateCurrentCondition, { flush: 'post' })
  watch([
    () => runtime.session.value?.status,
    () => activeMission()?.terminalReason
  ], ([status, terminalReason]) => {
    if (activeChapterId.value !== TUTORIAL_CHAPTER || !['FAILED', 'STOPPED', 'EXPIRED'].includes(String(status || ''))) return
    if (!['GROUND_BATTERY_DEPLETED', 'AIR_BATTERY_DEPLETED'].includes(String(terminalReason || ''))) return
    const category = terminalReason === 'GROUND_BATTERY_DEPLETED' ? 'GROUND' : 'AIR'
    const label = category === 'GROUND' ? '地面车辆' : '空中设备'
    void routeToFleetRecovery({
      code: terminalReason,
      category,
      message: `因为${label}电量已耗尽，教程任务无法继续。`,
      action: '请先点击“召回”让设备进入车库充电，电量恢复后重新出站，再开始教程 00。'
    })
  }, { flush: 'post' })
  watch(effectiveText, startTypewriter)

  onMounted(() => {
    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    reducedMotion.value = motionQuery.matches
    motionQuery.addEventListener?.('change', onMotionPreferenceChange)
    document.addEventListener('click', onDocumentClick, true)
    document.addEventListener('pointerdown', onDocumentPointerDown, true)
    document.addEventListener('keydown', onDocumentKeydown, true)
    window.addEventListener('resize', updateTargetRect)
    window.addEventListener('scroll', updateTargetRect, true)
    hydrateProgress()
  })

  onBeforeUnmount(() => {
    void releaseTutorialPause()
    runtime.clearTutorialGenerationPreset()
    runtime.clearTutorialRedConflictLocked()
    runtime.clearTutorialRewindLocked()
    runtime.clearTutorialMissionReactionsSuppressed()
    clearTransitionTimer()
    if (typewriterTimer) window.clearInterval(typewriterTimer)
    if (targetDelayTimer) window.clearTimeout(targetDelayTimer)
    targetMutationObserver?.disconnect()
    targetResizeObserver?.disconnect()
    targetElement.value?.classList.remove('tutorial-target-active')
    motionQuery?.removeEventListener?.('change', onMotionPreferenceChange)
    document.removeEventListener('click', onDocumentClick, true)
    document.removeEventListener('pointerdown', onDocumentPointerDown, true)
    document.removeEventListener('keydown', onDocumentKeydown, true)
    window.removeEventListener('resize', updateTargetRect)
    window.removeEventListener('scroll', updateTargetRect, true)
  })

  return {
    state: readonly(state),
    phase,
    currentStep,
    currentChapter: activeChapter,
    selectedChapter,
    activeChapterId: readonly(activeChapterId),
    selectedChapterId: readonly(selectedChapterId),
    isActive,
    isActionMode,
    plannerAware,
    fleetAware,
    replayAllowed,
    ready,
    statuses,
    progressStatus,
    chapterCards,
    beaconChapterIndex,
    beaconAttention,
    manualExpanded,
    displayedText,
    typingComplete,
    targetElement: readonly(targetElement),
    targetRect: readonly(targetRect),
    spotlightRect,
    targetSyncDelayed,
    wrongPulseKey,
    reducedMotion,
    speaker,
    expression,
    chapterPreparing,
    skipConfirmOpen: readonly(skipConfirmOpen),
    skipError: readonly(skipError),
    skipInFlight: readonly(skipInFlight),
    selectChapter,
    startTutorial,
    skipTutorial,
    confirmSkipTutorial,
    cancelSkipTutorial,
    toggleManual,
    advanceDialogue
  }
}
