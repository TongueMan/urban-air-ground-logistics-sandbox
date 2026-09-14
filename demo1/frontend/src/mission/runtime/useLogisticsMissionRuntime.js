import { computed, onBeforeUnmount, readonly, ref } from 'vue'
import { changeSpeed, createSession, executeAirspaceAction as postAirspaceAction, executeGroundRoutingCommand as postGroundRoutingCommand, executeSignalCommand as postSignalCommand, generateTaskInstance, getCurrentSession, getMission, getRunReplay, getScenarioTemplates, getTaskHistory, getTutorials, restoreRewindCheckpoint as postRestoreRewindCheckpoint, runEventsUrl, sessionEventsUrl, startTaskRun, stopSession, syncTutorialProgress } from '../../api/demo'
import { createMissionContext } from '../context/createMissionContext'
import { mergeDemoDelta } from '../adapters/mergeDemoDelta.mjs'

const TERMINAL_STATES = new Set(['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'])
const RUNTIME_WATCHDOG_INTERVAL_MS = 3000
const RUNTIME_STALL_THRESHOLD_MS = 8000
const FALLBACK_POLL_INTERVAL_MS = 4000

export function useLogisticsMissionRuntime({ onEconomy } = {}) {
  const context = createMissionContext()
  const busy = ref(false)
  const initialized = ref(false)
  const notice = ref('')
  const timeScale = ref(1)
  const lastActiveTimeScale = ref(1)
  const timeControlBusy = ref(false)
  const rewindBusy = ref(false)
  const rewindError = ref('')
  const lastRewindAck = ref(null)
  const signalCommandBusy = ref(false)
  const signalCommandError = ref('')
  const lastCommandAck = ref(null)
  const scenarioTemplates = ref([])
  const taskPreview = ref(null)
  const tutorialGenerationPreset = ref(null)
  const tutorialRedConflictLocked = ref(false)
  const tutorialRewindLocked = ref(false)
  const tutorialMissionReactionsSuppressed = ref(false)
  const tutorialState = ref({ items: [], capabilities: {} })
  const planningMode = ref('BASIC')
  const selectedBaselineRouteCandidateId = ref('')
  const groundRouteCommandBusy = ref(false)
  const lastGroundRouteCommand = ref(null)
  const historyItems = ref([])
  const plannerOpen = ref(false)
  const historyOpen = ref(false)
  const replayBundle = ref(null)
  const selectedAirspaceId = ref('')
  const mapFollowingDeviceId = ref('')
  const tutorialMapCameraTarget = ref('')
  const airspaceActionBusy = ref(false)
  const lastAirspaceAction = ref(null)
  const latestEconomyTransaction = ref(null)
  const latestAirspaceWarning = ref(null)
  let eventSource = null
  let pollTimer = null
  let runtimeWatchdogTimer = null
  let observedSessionId = ''
  let lastObservedSimulationMs = 0
  let lastRuntimeAdvanceAt = Date.now()
  let recoveryInFlight = false
  let groundCommandTimer = null
  let queuedGroundIntent = null
  let groundCommandSequence = 0
  let groundClickTimes = []
  let lastGroundClickHintAt = 0

  const session = computed(() => context.mission.value.source.session)
  const hasSession = computed(() => Boolean(session.value?.id))
  const isTerminal = computed(() => TERMINAL_STATES.has(String(session.value?.status || '')))
  const advancedRoutingUnlocked = computed(() => Boolean(tutorialState.value?.capabilities?.advancedGroundRoutingUnlocked))
  const tutorial02Available = computed(() => tutorialState.value?.items?.some(item => item.tutorialId === 'TUTORIAL-02-GROUND-COOP' && item.availability === 'AVAILABLE'))
  const tutorial02Completed = computed(() => tutorialState.value?.items?.some(item => item.tutorialId === 'TUTORIAL-02-GROUND-COOP' && item.status === 'COMPLETED'))

  function applySnapshot(payload, { mergeEconomy = true } = {}) {
    if (!payload) return
    if (payload.session) {
      const nextSessionId = String(payload.session.id || '')
      const nextSimulationMs = Number(payload.session.simulationElapsedMs || 0)
      if (nextSessionId !== observedSessionId || nextSimulationMs !== lastObservedSimulationMs) {
        observedSessionId = nextSessionId
        lastObservedSimulationMs = nextSimulationMs
        lastRuntimeAdvanceAt = Date.now()
      }
      if (TERMINAL_STATES.has(String(payload.session.status || ''))) {
        stopFallbackPolling()
        stopRuntimeWatchdog()
      }
    }
    context.ingestSnapshot(payload)
    if (payload.session) {
      const nextScale = Number(payload.session.timeScale ?? 1)
      timeScale.value = nextScale
      if (nextScale > 0) lastActiveTimeScale.value = nextScale
    }
    if (mergeEconomy && payload.mission?.economy) onEconomy?.(payload.mission.economy)
  }

  async function refreshMission() {
    if (!session.value?.id) return
    applySnapshot(await getMission(session.value.id))
  }

  function stopFallbackPolling() {
    if (pollTimer) window.clearInterval(pollTimer)
    pollTimer = null
  }

  function startFallbackPolling() {
    if (pollTimer) return
    pollTimer = window.setInterval(() => {
      refreshMission().catch(() => { notice.value = '任务连接暂未恢复，系统会继续自动重试。' })
    }, FALLBACK_POLL_INTERVAL_MS)
  }

  function stopRuntimeWatchdog() {
    if (runtimeWatchdogTimer) window.clearInterval(runtimeWatchdogTimer)
    runtimeWatchdogTimer = null
  }

  async function recoverStalledRuntime() {
    if (recoveryInFlight) return
    if (session.value?.status !== 'RUNNING' || Number(timeScale.value) <= 0) return
    if (context.timeMode.value !== 'LIVE') return
    if (Date.now() - lastRuntimeAdvanceAt < RUNTIME_STALL_THRESHOLD_MS) return
    recoveryInFlight = true
    // Give the refreshed stream a full watchdog window before another attempt.
    lastRuntimeAdvanceAt = Date.now()
    notice.value = '任务连接正在自动恢复…'
    try {
      await refreshMission()
      if (session.value?.status === 'RUNNING') connectEvents()
    } catch {
      notice.value = '任务连接暂未恢复，系统会继续自动重试。'
      startFallbackPolling()
    } finally {
      recoveryInFlight = false
    }
  }

  function startRuntimeWatchdog() {
    if (runtimeWatchdogTimer) return
    lastRuntimeAdvanceAt = Date.now()
    runtimeWatchdogTimer = window.setInterval(recoverStalledRuntime, RUNTIME_WATCHDOG_INTERVAL_MS)
  }

  function applyDelta(type, event) {
    const delta = JSON.parse(event.data)
    const merged = mergeDemoDelta(context.rawSnapshot.value, type, delta)
    if (!merged) {
      refreshMission().catch(() => {})
      return
    }
    applySnapshot(merged)
    if (type === 'command-ack') lastCommandAck.value = delta.command
    if (type === 'ground-route-command') lastGroundRouteCommand.value = delta.command || null
    if (type === 'economy-delta') {
      latestEconomyTransaction.value = delta.transaction ? { ...delta.transaction, receivedAt: Date.now() } : null
      onEconomy?.(delta.economy)
    }
    if (type === 'airspace-warning') {
      latestAirspaceWarning.value = { ...delta.warning, receivedAt: Date.now() }
      if (!(tutorialRedConflictLocked.value && isAbsoluteNoFlyVolume(delta.warning.volumeId))) {
        selectAirspace(delta.warning.volumeId)
      }
      notice.value = delta.warning.message || '绝对禁飞区已进入处置范围。'
    }
  }

  function connectEvents() {
    eventSource?.close()
    if (!session.value?.id || isTerminal.value) {
      stopRuntimeWatchdog()
      return
    }
    startRuntimeWatchdog()
    eventSource = new EventSource(session.value.taskInstanceId ? runEventsUrl(session.value.id) : sessionEventsUrl(session.value.id), { withCredentials: true })
    eventSource.onopen = () => { notice.value = ''; stopFallbackPolling() }
    eventSource.addEventListener('snapshot', event => {
      notice.value = ''
      applySnapshot(JSON.parse(event.data))
    })
    ;['mission-delta', 'track-delta', 'signal-delta', 'command-ack', 'airspace-delta', 'airspace-warning', 'economy-delta', 'rewind-checkpoint', 'ground-route-command', 'ground-route-delta', 'pace-vehicle-delta', 'tutorial-progress'].forEach(type => {
      eventSource.addEventListener(type, event => applyDelta(type, event))
    })
    eventSource.addEventListener('session-end', event => {
      applySnapshot(JSON.parse(event.data))
      eventSource?.close()
      loadTutorialState().then(result => { tutorialState.value = result || tutorialState.value }).catch(() => {})
    })
    eventSource.onerror = () => {
      notice.value = '实时链路正在重连，已保留最近一次任务数据。'
      startFallbackPolling()
    }
  }

  async function initialize() {
    try {
      const [templateResult, historyResult, tutorialResult] = await Promise.all([getScenarioTemplates(), getTaskHistory(20), loadTutorialState()])
      scenarioTemplates.value = templateResult?.items || []
      historyItems.value = historyResult?.items || []
      tutorialState.value = tutorialResult || { items: [], capabilities: {} }
      applySnapshot(await getCurrentSession())
      if (session.value?.id) {
        // /current already returns the complete visitor snapshot. Fetching the
        // same mission again doubled refresh traffic and introduced a repaint race.
        connectEvents()
      }
    } catch (error) {
      notice.value = '服务正在启动，请稍后刷新。'
    } finally {
      initialized.value = true
    }
  }

  async function generateTask(payload) {
    busy.value = true
    notice.value = ''
    try {
      taskPreview.value = await generateTaskInstance(payload)
      selectedBaselineRouteCandidateId.value = ''
      plannerOpen.value = true
      context.ingestSnapshot({ session: null, mission: taskPreview.value.plan, devices: previewDevices(taskPreview.value.plan) })
      return taskPreview.value
    } catch (error) {
      notice.value = error.message
      if (tutorialGenerationPreset.value?.tutorialId === 'TUTORIAL-02-GROUND-COOP') {
        await refreshTutorialState().catch(() => null)
      }
      throw error
    } finally { busy.value = false }
  }

  async function startPreview() {
    if (!taskPreview.value?.taskId) return null
    busy.value = true
    notice.value = ''
    try {
      const selected = selectedBaselineRouteCandidateId.value
      applySnapshot(await startTaskRun(taskPreview.value.taskId, selected ? { selectedBaselineRouteCandidateId: selected } : null))
      taskPreview.value = null
      plannerOpen.value = false
      tutorialGenerationPreset.value = null
      selectedAirspaceId.value = ''
      lastAirspaceAction.value = null
      lastRewindAck.value = null
      replayBundle.value = null
      context.returnToLive()
      connectEvents()
      return session.value
    } catch (error) {
      notice.value = error.message
      throw error
    } finally { busy.value = false }
  }

  function discardTaskPreview() {
    taskPreview.value = null
    selectedBaselineRouteCandidateId.value = ''
    notice.value = ''
    context.ingestSnapshot(null)
  }

  async function refreshHistory() {
    const result = await getTaskHistory(20)
    historyItems.value = result?.items || []
    return historyItems.value
  }

  async function openHistory() {
    await refreshHistory()
    historyOpen.value = true
  }

  async function openHistoricalRun(runId) {
    busy.value = true
    try {
      replayBundle.value = await getRunReplay(runId)
      applySnapshot(replayBundle.value.snapshot, { mergeEconomy: false })
      context.enterReplay(100)
      historyOpen.value = false
    } catch (error) { notice.value = error.message }
    finally { busy.value = false }
  }

  function openPlanner() { plannerOpen.value = true; historyOpen.value = false }
  function openPlannerMode(mode = 'BASIC') {
    const nextMode = mode === 'ADVANCED' ? 'ADVANCED' : 'BASIC'
    const previewMode = String(taskPreview.value?.plan?.planningMode || 'BASIC')
    const previewIsTutorial = Boolean(taskPreview.value?.plan?.tutorialId)
    const preserveProloguePreset = nextMode === 'BASIC'
      && String(tutorialGenerationPreset.value?.seed || '') === '1204'
      && !tutorialGenerationPreset.value?.tutorialId
    if (!preserveProloguePreset) tutorialGenerationPreset.value = null
    if (taskPreview.value && (previewMode !== nextMode || previewIsTutorial)) discardTaskPreview()
    planningMode.value = nextMode
    openPlanner()
  }
  function startGroundTutorial() {
    const tutorialId = 'TUTORIAL-02-GROUND-COOP'
    const previewTutorialId = String(taskPreview.value?.plan?.tutorialId || '')
    if (taskPreview.value && (String(taskPreview.value?.plan?.planningMode || 'BASIC') !== 'ADVANCED'
      || previewTutorialId !== tutorialId)) discardTaskPreview()
    planningMode.value = 'ADVANCED'
    tutorialGenerationPreset.value = Object.freeze({ seed: '2026091202', tutorialId })
    openPlanner()
  }
  function closePlanner() { plannerOpen.value = false }
  function closeHistory() { historyOpen.value = false }
  function setTutorialGenerationPreset(preset = null) {
    const seed = String(preset?.seed || '').trim()
    tutorialGenerationPreset.value = seed ? Object.freeze({ seed, tutorialId: preset?.tutorialId || null }) : null
  }
  function clearTutorialGenerationPreset() { tutorialGenerationPreset.value = null }
  function isAbsoluteNoFlyVolume(volumeId) {
    const volumes = context.rawSnapshot.value?.mission?.airspace?.runtimeVolumes
      || context.rawSnapshot.value?.mission?.airspace?.volumes
      || []
    return volumes.some(volume => (
      String(volume.id || '') === String(volumeId || '')
      && String(volume.ruleType || '') === 'ABSOLUTE_NO_FLY'
    ))
  }
  function setTutorialRedConflictLocked(locked = true) {
    tutorialRedConflictLocked.value = Boolean(locked)
    if (tutorialRedConflictLocked.value && isAbsoluteNoFlyVolume(selectedAirspaceId.value)) {
      selectedAirspaceId.value = ''
    }
  }
  function clearTutorialRedConflictLocked() { tutorialRedConflictLocked.value = false }
  function setTutorialRewindLocked(locked = true) {
    tutorialRewindLocked.value = Boolean(locked)
    if (tutorialRewindLocked.value && context.timeMode.value === 'REPLAY') context.returnToLive()
  }
  function clearTutorialRewindLocked() { tutorialRewindLocked.value = false }
  function setTutorialMissionReactionsSuppressed(suppressed = true) {
    tutorialMissionReactionsSuppressed.value = Boolean(suppressed)
  }
  function clearTutorialMissionReactionsSuppressed() { tutorialMissionReactionsSuppressed.value = false }
  function setMapFollowingDevice(deviceId = '') { mapFollowingDeviceId.value = String(deviceId || '') }
  function setTutorialMapCameraTarget(target = '') { tutorialMapCameraTarget.value = String(target || '') }

  async function startMission() {
    busy.value = true
    notice.value = ''
    context.clearFocus()
    context.returnToLive()
    try {
      applySnapshot(await createSession())
      connectEvents()
      await refreshMission()
    } catch (error) {
      notice.value = error.message
    } finally {
      busy.value = false
    }
  }

  async function updateSpeed() {
    if (!session.value?.id) return
    try { applySnapshot(await changeSpeed(session.value.id, timeScale.value)) }
    catch (error) { notice.value = error.message }
  }

  async function pauseMission() {
    if (!session.value?.id || session.value.status !== 'RUNNING') return false
    if (Number(timeScale.value) === 0) return true
    if (timeControlBusy.value) return false
    timeControlBusy.value = true
    try {
      applySnapshot(await changeSpeed(session.value.id, 0))
      return true
    } catch (error) {
      notice.value = error.message
      return false
    } finally { timeControlBusy.value = false }
  }

  async function resumeMission(requestedScale = null) {
    if (!session.value?.id || session.value.status !== 'RUNNING') return false
    if (timeControlBusy.value) return false
    timeControlBusy.value = true
    context.returnToLive()
    try {
      const scale = Number(requestedScale)
      applySnapshot(await changeSpeed(session.value.id, scale > 0 ? scale : (Number(lastActiveTimeScale.value) || 1)))
      connectEvents()
      return true
    } catch (error) {
      notice.value = error.message
      return false
    } finally { timeControlBusy.value = false }
  }

  async function previewAt(progress) {
    if (!session.value?.id) return false
    const wasReplaying = context.timeMode.value === 'REPLAY'
    // Move the presentation cursor before waiting for the pause request. Range
    // input events can arrive much faster than that request; entering replay
    // synchronously lets every following event update the same cursor instead
    // of being overwritten later by the first (now stale) drag position.
    context.enterReplay(progress)
    if (session.value.status === 'RUNNING' && Number(timeScale.value) > 0) {
      const paused = await pauseMission()
      if (!paused) {
        if (!wasReplaying) context.returnToLive()
        return false
      }
    }
    return true
  }

  async function restoreCheckpoint(checkpointId) {
    if (!session.value?.id || !checkpointId) return null
    if (rewindBusy.value) return null
    const runId = session.value.id
    rewindBusy.value = true
    rewindError.value = ''
    lastRewindAck.value = null
    const rewindId = globalThis.crypto?.randomUUID?.() || `rewind-${Date.now()}-${Math.random().toString(16).slice(2)}`
    try {
      // Selecting a checkpoint pauses the run asynchronously. A final simulation
      // delta can therefore arrive after the pause response and leave the local
      // revision one step behind. Refresh immediately before the optimistic
      // restore so the first confirmation click uses the server's settled
      // revision instead of forcing the player to retry.
      const latestSnapshot = await getMission(runId)
      applySnapshot(latestSnapshot)
      const result = await postRestoreRewindCheckpoint(runId, checkpointId, {
        rewindId,
        expectedRevision: Number(latestSnapshot?.revision || 0)
      })
      replayBundle.value = null
      latestEconomyTransaction.value = null
      latestAirspaceWarning.value = null
      lastCommandAck.value = null
      signalCommandError.value = ''
      lastRewindAck.value = result.rewind
      applySnapshot(result.snapshot)
      context.returnToLive()
      selectedAirspaceId.value = String(result.rewind?.volumeId || '')
      notice.value = result.rewind?.message || '已恢复到决策检查点。'
      connectEvents()
      return result
    } catch (error) {
      rewindError.value = error.message
      notice.value = error.message
      throw error
    } finally { rewindBusy.value = false }
  }

  function prepareNewDelivery() {
    eventSource?.close()
    stopFallbackPolling()
    stopRuntimeWatchdog()
    context.clearFocus()
    context.returnToLive()
    taskPreview.value = null
    planningMode.value = 'BASIC'
    selectedBaselineRouteCandidateId.value = ''
    replayBundle.value = null
    selectedAirspaceId.value = ''
    lastAirspaceAction.value = null
    lastRewindAck.value = null
    clearTutorialRedConflictLocked()
    clearTutorialRewindLocked()
    clearTutorialMissionReactionsSuppressed()
    latestAirspaceWarning.value = null
    notice.value = ''
    context.ingestSnapshot(null)
    historyOpen.value = false
    plannerOpen.value = true
  }

  async function endMission() {
    if (!session.value?.id) return true
    try {
      applySnapshot(await stopSession(session.value.id))
      eventSource?.close()
      return true
    } catch (error) {
      notice.value = error.message
      return false
    }
  }

  async function executeSignalCommand(signalId, type, expectedSignalStatus) {
    if (!session.value?.id || !signalId) return null
    signalCommandBusy.value = true
    signalCommandError.value = ''
    lastCommandAck.value = null
    const commandId = globalThis.crypto?.randomUUID?.() || `workflow-${Date.now()}-${Math.random().toString(16).slice(2)}`
    try {
      const result = await postSignalCommand(session.value.id, signalId, { commandId, type, expectedSignalStatus })
      applySnapshot(result.snapshot)
      lastCommandAck.value = result.command
      return result.command
    } catch (error) {
      signalCommandError.value = error.message
      throw error
    } finally {
      signalCommandBusy.value = false
    }
  }

  function selectAirspace(volumeId) {
    if (tutorialRedConflictLocked.value && isAbsoluteNoFlyVolume(volumeId)) return false
    context.closeActionMode()
    selectedAirspaceId.value = String(volumeId || '')
    return true
  }
  function closeAirspace() { selectedAirspaceId.value = '' }
  async function executeAirspaceAction(volumeId, actionType) {
    if (!session.value?.id || !volumeId) return null
    airspaceActionBusy.value = true
    lastAirspaceAction.value = null
    try {
      const result = await postAirspaceAction(session.value.id, volumeId, actionType)
      applySnapshot(result.snapshot)
      lastAirspaceAction.value = result.action || null
      return lastAirspaceAction.value
    } catch (error) { notice.value = error.message; throw error }
    finally { airspaceActionBusy.value = false }
  }

  async function dispatchGroundCommand(intent) {
    if (!session.value?.id || session.value?.planningMode !== 'ADVANCED') return null
    groundRouteCommandBusy.value = true
    const sequenceFloor = Number(context.rawSnapshot.value?.mission?.groundRouting?.commandSequence || 0)
    groundCommandSequence = Math.max(groundCommandSequence, sequenceFloor) + 1
    const command = {
      commandId: globalThis.crypto?.randomUUID?.() || `ground-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      commandSequence: groundCommandSequence,
      ...intent
    }
    try {
      const result = await postGroundRoutingCommand(session.value.id, command)
      lastGroundRouteCommand.value = result.command || null
      if (result.groundRouting) context.ingestSnapshot({ ...context.rawSnapshot.value, mission: { ...context.rawSnapshot.value.mission, groundRouting: result.groundRouting } })
      return result.command
    } catch (error) { notice.value = error.message; throw error }
    finally { groundRouteCommandBusy.value = false }
  }
  function setGroundTemporaryTarget(intent) {
    const limits = context.rawSnapshot.value?.mission?.groundRoutingLimits || {}
    const now = Date.now()
    const burstWindowMs = Math.max(100, Number(limits.clickBurstWindowMs || 2000))
    const burstThreshold = Math.max(1, Number(limits.clickBurstThreshold || 5))
    const burstSuppressionMs = Math.max(1000, Number(limits.clickBurstSuppressionMs || 10000))
    groundClickTimes = groundClickTimes.filter(timestamp => now - timestamp <= burstWindowMs)
    groundClickTimes.push(now)
    if (groundClickTimes.length > burstThreshold && now - lastGroundClickHintAt >= burstSuppressionMs) {
      notice.value = '调度请求较频繁，系统将只执行最新目标'
      lastGroundClickHintAt = now
    }
    queuedGroundIntent = { type: 'SET_TEMPORARY_TARGET', ...intent }
    if (groundCommandTimer) window.clearTimeout(groundCommandTimer)
    groundCommandTimer = window.setTimeout(() => {
      const latest = queuedGroundIntent; queuedGroundIntent = null; groundCommandTimer = null
      dispatchGroundCommand(latest).catch(() => {})
    }, Math.max(0, Number(limits.clickMergeMs || 300)))
  }
  function returnGroundToBaseline() {
    if (groundCommandTimer) window.clearTimeout(groundCommandTimer)
    groundCommandTimer = null; queuedGroundIntent = null
    return dispatchGroundCommand({ type: 'RETURN_TO_BASELINE' })
  }

  async function loadTutorialState() {
    const result = await getTutorials()
    const migrations = [
      ['skyfleet.tutorial.prologue.v3', 'prologue', '3'],
      ['skyfleet.tutorial.fleet-center.v1', 'fleet-center', '1']
    ]
    for (const [key, tutorialId, tutorialVersion] of migrations) {
      try {
        const local = JSON.parse(localStorage.getItem(key) || 'null')
        const rawStatus = String(local?.status || local?.phase || '').toUpperCase()
        const status = rawStatus.includes('COMPLETE') ? 'COMPLETED' : rawStatus.includes('SKIP') ? 'SKIPPED' : null
        if (status) await syncTutorialProgress({ tutorialId, tutorialVersion, status })
      } catch { /* a malformed legacy record must not block mission startup */ }
    }
    return getTutorials()
  }

  async function refreshTutorialState() {
    const result = await getTutorials()
    tutorialState.value = result || { items: [], capabilities: {} }
    return tutorialState.value
  }

  async function syncTutorialStatus(tutorialId, tutorialVersion, status) {
    const result = await syncTutorialProgress({ tutorialId, tutorialVersion, status })
    tutorialState.value = result || tutorialState.value
    return tutorialState.value
  }

  function dispose() {
    eventSource?.close()
    stopFallbackPolling()
    stopRuntimeWatchdog()
    clearTutorialRedConflictLocked()
    clearTutorialRewindLocked()
    clearTutorialMissionReactionsSuppressed()
    if (groundCommandTimer) window.clearTimeout(groundCommandTimer)
  }

  onBeforeUnmount(dispose)

  return {
    context,
    initialized: readonly(initialized),
    session,
    hasSession,
    isTerminal,
    busy,
    notice,
    timeScale,
    lastActiveTimeScale,
    timeControlBusy,
    rewindBusy,
    rewindError,
    lastRewindAck,
    signalCommandBusy,
    signalCommandError,
    lastCommandAck,
    scenarioTemplates,
    taskPreview,
    tutorialGenerationPreset: readonly(tutorialGenerationPreset),
    tutorialRedConflictLocked: readonly(tutorialRedConflictLocked),
    tutorialRewindLocked: readonly(tutorialRewindLocked),
    tutorialMissionReactionsSuppressed: readonly(tutorialMissionReactionsSuppressed),
    tutorialState: readonly(tutorialState),
    tutorial02Available,
    tutorial02Completed,
    advancedRoutingUnlocked,
    planningMode,
    selectedBaselineRouteCandidateId,
    groundRouteCommandBusy: readonly(groundRouteCommandBusy),
    lastGroundRouteCommand: readonly(lastGroundRouteCommand),
    historyItems,
    plannerOpen,
    historyOpen,
    replayBundle,
    selectedAirspaceId,
    mapFollowingDeviceId: readonly(mapFollowingDeviceId),
    tutorialMapCameraTarget: readonly(tutorialMapCameraTarget),
    airspaceActionBusy,
    lastAirspaceAction: readonly(lastAirspaceAction),
    latestEconomyTransaction,
    latestAirspaceWarning,
    initialize,
    generateTask,
    discardTaskPreview,
    startPreview,
    refreshHistory,
    openHistory,
    openHistoricalRun,
    openPlanner,
    openPlannerMode,
    startGroundTutorial,
    closePlanner,
    closeHistory,
    setTutorialGenerationPreset,
    clearTutorialGenerationPreset,
    setTutorialRedConflictLocked,
    clearTutorialRedConflictLocked,
    setTutorialRewindLocked,
    clearTutorialRewindLocked,
    setTutorialMissionReactionsSuppressed,
    clearTutorialMissionReactionsSuppressed,
    setMapFollowingDevice,
    setTutorialMapCameraTarget,
    refreshMission,
    startMission,
    updateSpeed,
    pauseMission,
    resumeMission,
    previewAt,
    restoreCheckpoint,
    prepareNewDelivery,
    endMission,
    executeSignalCommand,
    selectAirspace,
    closeAirspace,
    executeAirspaceAction,
    setGroundTemporaryTarget,
    returnGroundToBaseline,
    refreshTutorialState,
    syncTutorialStatus,
    dispose
  }
}

function previewDevices(plan = {}) {
  return (plan.actors || []).map(actor => {
    const route = (plan.routes || []).find(item => item.deviceId === actor.id)
    const anchorRoute = actor.kind === 'UAV'
      ? (plan.routes || []).find(item => item.deviceId === actor.carrierActorId)
      : route
    const point = anchorRoute?.points?.[0] || [0, 0, 0]
    return {
      deviceId: actor.id,
      deviceType: actor.deviceType,
      deviceName: actor.name,
      actorKind: actor.kind,
      actorRole: actor.role,
      capabilities: actor.capabilities || [],
      longitude: Number(point[0]), latitude: Number(point[1]), altitude: actor.kind === 'UAV' ? Number(point[2] || 0) + 2 : Number(point[2] || 0),
      sensorData: { missionPhase: 'DOCKED', routeProgress: 0, routeDeviationMeters: 0, battery: actor.initialBattery || 100, linkQuality: 100, deliveryProgressPercent: 0 }
    }
  })
}
