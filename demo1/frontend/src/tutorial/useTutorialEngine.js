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
  FLEET_HUB_DEPENDENT_STEPS,
  FLEET_TUTORIAL_CHAPTER,
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
  const chapterPreparing = ref(false)
  const manualAttentionSeen = ref(false)
  const progressContext = ref({})

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
  let fleetRecoveryInFlight = false

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
  const plannerAware = computed(() => activeChapterId.value === TUTORIAL_CHAPTER && Boolean(runtime.plannerOpen.value))
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
    unlocked: tutorialChapterUnlocked(chapter.id, statuses.value)
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
  const speaker = computed(() => state.value.wrongAttempts >= 3 && isActionMode.value
    ? 'anan'
    : currentStep.value?.speaker)
  const expression = computed(() => state.value.wrongAttempts >= 3 && isActionMode.value
    ? 'awkward'
    : currentStep.value?.expression)
  const effectiveText = computed(() => {
    if (targetSyncDelayed.value && !targetElement.value) return '目标仍在同步，请稍候。界面准备好后会自动继续。'
    if (generationFailed.value && currentStep.value.id === 'A03') return '方案没有通过这次校验。检查系统提示后，再点击一次生成本局任务。'
    if (startFailed.value && currentStep.value.id === 'A04') return '任务没有成功启动。保留当前方案，再点击一次“开始配送”即可重试。'
    if (detourFailed.value && currentStep.value.id === 'A06') return '绕飞没有成功应用。查看系统提示后，再点击一次“从侧面绕飞”。'
    if (state.value.wrongAttempts >= 3 && isActionMode.value) return '先点亮起的目标。其他操作等教程结束后再试。'
    if (state.value.waiting && currentStep.value.id === 'A03') return '正在生成与校验路线、配送点、奖励和四种空域…'
    if (state.value.waiting && currentStep.value.id === 'A04') return '正在启动真实配送任务并暂停仿真…'
    if (state.value.waiting && currentStep.value.id === 'A06') return '正在应用绕飞航线…'
    return currentStep.value?.text || ''
  })

  function dispatch(event) {
    state.value = tutorialReducer(state.value, event, steps.value)
  }

  function clearTransitionTimer() {
    if (transitionTimer) window.clearTimeout(transitionTimer)
    transitionTimer = null
  }

  function recordProgress(chapterId, status = 'in_progress', stepId) {
    const chapter = getTutorialChapter(chapterId)
    const context = chapter.id === TUTORIAL_CHAPTER ? progressContext.value : {}
    const next = createProgressRecord(stepId || chapter.steps[0].id, status, Date.now(), chapter.id, context)
    window.localStorage.setItem(chapter.storageKey, JSON.stringify(next))
    progressRecords.value = { ...progressRecords.value, [chapter.id]: next }
    return next
  }

  function activeMission() {
    return runtime.context?.rawSnapshot?.value?.mission || runtime.context?.mission?.value?.source?.mission || null
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

  function redDiamondCollected() {
    const id = String(progressContext.value.redDiamondId || '')
    const collected = activeMission()?.economy?.collectedDiamondIds || []
    return Boolean(id && collected.map(String).includes(id))
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
    if (!card?.unlocked || !ready.value || !replayAllowed.value || chapterPreparing.value) return false

    chapterPreparing.value = true
    try {
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
      progressContext.value = {}
      generationStarted = false
      startActionStarted = false
      detourActionStarted = false
      if (chapterId === TUTORIAL_CHAPTER) runtime.setTutorialGenerationPreset({ seed: PROLOGUE_TUTORIAL_SEED })
      else runtime.clearTutorialGenerationPreset()
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
    if (finishedChapterId !== TUTORIAL_CHAPTER) {
      manualExpanded.value = false
      return
    }
    selectedChapterId.value = FLEET_TUTORIAL_CHAPTER
    manualExpanded.value = false
  }

  function closeActiveChapter({ promptNext = false } = {}) {
    const finishedChapterId = activeChapterId.value
    clearTransitionTimer()
    runtime.clearTutorialGenerationPreset()
    dispatch({ type: 'CLOSE' })
    activeChapterId.value = ''
    if (promptNext) promptNextChapter(finishedChapterId)
  }

  async function routeToFleetRecovery(issue) {
    if (!issue || fleetRecoveryInFlight) return false
    fleetRecoveryInFlight = true
    try {
      if (activeChapterId.value === TUTORIAL_CHAPTER) closeActiveChapter()
      else {
        runtime.clearTutorialGenerationPreset()
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

  async function skipTutorial(chapterId = activeChapterId.value || selectedChapterId.value) {
    const chapter = getTutorialChapter(chapterId)
    if (!tutorialChapterUnlocked(chapter.id, statuses.value)) return
    const active = activeChapterId.value === chapter.id && isActive.value
    recordProgress(chapter.id, 'skipped', active ? currentStep.value.id : chapter.steps[0].id)
    if (active && chapter.id === TUTORIAL_CHAPTER) await releaseTutorialPause()
    if (chapter.id === TUTORIAL_CHAPTER) runtime.clearTutorialGenerationPreset()
    if (active) closeActiveChapter({ promptNext: chapter.id === TUTORIAL_CHAPTER })
    else if (chapter.id === TUTORIAL_CHAPTER) promptNextChapter(chapter.id)
    else manualExpanded.value = false
  }

  function toggleManual() {
    if (isActive.value) return
    if (!manualExpanded.value && !manualAttentionSeen.value) {
      const record = createManualAttentionRecord()
      window.localStorage.setItem(TUTORIAL_ATTENTION_STORAGE_KEY, JSON.stringify(record))
      manualAttentionSeen.value = true
    }
    manualExpanded.value = !manualExpanded.value
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
    if (currentStep.value.id === 'A05') {
      window.setTimeout(() => {
        if (String(runtime.selectedAirspaceId.value || '') === String(progressContext.value.redVolumeId || '')) completeAction()
      }, 0)
      return
    }
    if (currentStep.value.id === 'A06' && !runtime.airspaceActionBusy.value && !state.value.waiting) {
      detourFailed.value = false
      detourActionStarted = true
      dispatch({ type: 'ACTION_WAIT' })
      return
    }
    if (currentStep.value.id === 'F01-A01') {
      window.setTimeout(() => fleetRuntime.isOpen.value && completeAction(), 0)
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

  function eventHitsTutorialControl(event) {
    return Boolean(event.composedPath?.().some(element => element?.dataset?.tutorialControl !== undefined))
  }

  function onDocumentClick(event) {
    if (!locksInteraction.value || eventHitsTutorialControl(event)) return
    if (eventHitsTarget(event)) {
      handleTargetAction(event)
      return
    }
    event.preventDefault()
    event.stopImmediatePropagation()
    registerWrongInteraction()
  }

  function onDocumentPointerDown(event) {
    if (locksInteraction.value && !eventHitsTutorialControl(event) && !eventHitsTarget(event)) {
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
      !eventHitsTarget(event) &&
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
    setTarget(document.querySelector(`[data-tutorial-id="${currentStep.value.targetId}"]`))
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
      attributeFilter: ['class', 'style', 'disabled']
    })
    targetDelayTimer = window.setTimeout(() => {
      if (!targetElement.value) targetSyncDelayed.value = true
    }, TARGET_DELAY_NOTICE)
  }

  function resumeChapter(chapterId, stepId, record = null) {
    activeChapterId.value = chapterId
    selectedChapterId.value = chapterId
    manualExpanded.value = false
    progressContext.value = chapterId === TUTORIAL_CHAPTER
      ? Object.fromEntries(['taskId', 'runId', 'redVolumeId', 'redDiamondId', 'pausedByTutorial', 'resumeTimeScale']
        .filter(key => record?.[key] !== undefined)
        .map(key => [key, record[key]]))
      : {}
    if (chapterId === TUTORIAL_CHAPTER && !record?.runId) {
      runtime.setTutorialGenerationPreset({ seed: PROLOGUE_TUTORIAL_SEED })
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
    const records = Object.fromEntries(TUTORIAL_CHAPTERS.map(chapter => [
      chapter.id,
      parseProgressRecord(window.localStorage.getItem(chapter.storageKey), chapter.id)
    ]))
    progressRecords.value = records

    const restoredStatuses = Object.fromEntries(TUTORIAL_CHAPTERS.map(chapter => [
      chapter.id,
      records[chapter.id]?.status || 'new'
    ]))
    const resumable = TUTORIAL_CHAPTERS.find(chapter => {
      if (!tutorialChapterUnlocked(chapter.id, restoredStatuses)) return false
      if (!canAutoResumeTutorial(records[chapter.id], {
        activeRunId: activeRunId(),
        isTerminal: runtime.isTerminal.value
      })) return false
      return Boolean(resolveResumeStep(records[chapter.id], {
        plannerOpen: runtime.plannerOpen.value,
        fleetHubOpen: fleetRuntime?.isOpen.value,
        chapterId: chapter.id,
        activeRunId: activeRunId()
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
        activeRunId: activeRunId()
      })
      resumeChapter(resumable.id, stepId, records[resumable.id])
      return
    }

    const next = TUTORIAL_CHAPTERS.find(chapter => (
      tutorialChapterUnlocked(chapter.id, restoredStatuses) && !records[chapter.id]
    ))
    const fallback = TUTORIAL_CHAPTERS.find(chapter => (
      tutorialChapterUnlocked(chapter.id, restoredStatuses) && records[chapter.id]
    )) || TUTORIAL_CHAPTERS.find(chapter => tutorialChapterUnlocked(chapter.id, restoredStatuses))
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

  async function completeDetourAction(action = selectedRedAction()) {
    if (detourCompletionInFlight || currentStep.value.id !== 'A06'
      || phase.value !== TUTORIAL_PHASES.ACTION || !tutorialRunMatches()) return
    if (String(action?.volumeId || '') !== String(progressContext.value.redVolumeId || '')
      || String(action?.runId || '') !== activeRunId()
      || action?.actionType !== 'DETOUR' || action?.status !== 'APPLIED') return
    detourCompletionInFlight = true
    try {
      const released = await releaseTutorialPause()
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
    if (phase.value !== TUTORIAL_PHASES.ACTION || activeChapterId.value !== TUTORIAL_CHAPTER) return
    if (currentStep.value.id === 'A02') {
      const checked = document.querySelector('[data-tutorial-id="mission-zone-count-four"] input[type="radio"]')?.checked
      if (checked) completeAction()
    } else if (currentStep.value.id === 'A04') {
      void ensureStartedTaskPaused()
    } else if (currentStep.value.id === 'A04-FOLLOW') {
      if (runtime.mapFollowingDeviceId.value) completeAction()
    } else if (currentStep.value.id === 'A04-OVERVIEW') {
      if (!runtime.mapFollowingDeviceId.value) completeAction()
    } else if (currentStep.value.id === 'A05') {
      if (String(runtime.selectedAirspaceId.value || '') === String(progressContext.value.redVolumeId || '')) completeAction()
    } else if (currentStep.value.id === 'A06') {
      void completeDetourAction()
    } else if (currentStep.value.id === 'WAIT-DIAMOND' && redDiamondCollected()) {
      completeAction()
    }
  }

  watch(() => runtime.initialized.value, hydrateProgress, { immediate: true })
  watch(() => [activeChapterId.value, currentStep.value.id, phase.value], () => {
    if (isActive.value && ![TUTORIAL_PHASES.INTRO, TUTORIAL_PHASES.COMPLETE].includes(phase.value)) {
      recordProgress(activeChapterId.value, 'in_progress', currentStep.value.id)
    }
    observeTarget()
    startTypewriter()
    nextTick(evaluateCurrentCondition)
    if (phase.value === TUTORIAL_PHASES.COMPLETE && activeChapterId.value) {
      const chapterId = activeChapterId.value
      if (chapterId === TUTORIAL_CHAPTER) void releaseTutorialPause()
      recordProgress(chapterId, 'completed', steps.value.at(-1).id)
      manualExpanded.value = false
      clearTransitionTimer()
      transitionTimer = window.setTimeout(
        () => closeActiveChapter({ promptNext: chapterId === TUTORIAL_CHAPTER }),
        reducedMotion.value ? 500 : COMPLETE_DURATION
      )
    }
  })
  watch(() => runtime.plannerOpen.value, open => {
    if (open && phase.value === TUTORIAL_PHASES.ACTION && currentStep.value.id === 'A01') completeAction()
    if (!open && activeChapterId.value === TUTORIAL_CHAPTER
      && PLANNER_DEPENDENT_STEPS.has(currentStep.value.id)) window.setTimeout(() => {
      if (activeChapterId.value !== TUTORIAL_CHAPTER || runtime.plannerOpen.value) return
      if (currentStep.value.id === 'A04' && activeTaskId() === String(progressContext.value.taskId || '')) {
        void ensureStartedTaskPaused()
        return
      }
      rewindToAction('A01')
    }, 0)
  })
  watch(() => fleetRuntime?.isOpen.value, open => {
    if (open && phase.value === TUTORIAL_PHASES.ACTION && currentStep.value.id === 'F01-A01') completeAction()
    if (!open && activeChapterId.value === FLEET_TUTORIAL_CHAPTER && FLEET_HUB_DEPENDENT_STEPS.has(currentStep.value.id)) {
      rewindToAction('F01-A01')
    }
  })
  watch([() => runtime.busy.value, () => runtime.taskPreview.value], ([busy, preview]) => {
    if (currentStep.value.id !== 'A03' || phase.value !== TUTORIAL_PHASES.ACTION || !state.value.waiting) return
    if (busy) {
      generationStarted = true
      return
    }
    if (!generationStarted) return
    if (preview && preview !== previewBeforeAction) {
      captureTaskContext(preview)
      const issue = tutorialFleetIssue(fleetRuntime.snapshot.value, preview)
      if (issue) void routeToFleetRecovery(issue)
      else completeAction()
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
    if (currentStep.value.id !== 'A04' || phase.value !== TUTORIAL_PHASES.ACTION || !state.value.waiting) return
    if (busy) return
    if (activeTaskId() === String(progressContext.value.taskId || '') && runtime.session.value?.status === 'RUNNING') {
      void ensureStartedTaskPaused()
    } else if (startActionStarted) {
      startFailed.value = true
      startActionStarted = false
      dispatch({ type: 'ACTION_RETRY' })
    }
  }, { flush: 'post' })
  watch(() => runtime.selectedAirspaceId.value, evaluateCurrentCondition, { flush: 'post' })
  watch(() => runtime.mapFollowingDeviceId.value, evaluateCurrentCondition, { flush: 'post' })
  watch([
    () => runtime.airspaceActionBusy.value,
    () => runtime.lastAirspaceAction?.value?.id,
    () => selectedRedAction()?.id
  ], ([busy]) => {
    if (currentStep.value.id !== 'A06' || phase.value !== TUTORIAL_PHASES.ACTION) return
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
    selectChapter,
    startTutorial,
    skipTutorial,
    toggleManual,
    advanceDialogue
  }
}
