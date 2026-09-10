import { computed, onBeforeUnmount, readonly, ref } from 'vue'
import { changeSpeed, createSession, executeAirspaceAction as postAirspaceAction, executeSignalCommand as postSignalCommand, generateTaskInstance, getCurrentSession, getMission, getRunReplay, getScenarioTemplates, getTaskHistory, requestSignalAdvisory, restoreRewindCheckpoint as postRestoreRewindCheckpoint, runEventsUrl, sessionEventsUrl, startTaskRun, stopSession, submitAdvisoryDecision as postAdvisoryDecision } from '../../api/demo'
import { createMissionContext } from '../context/createMissionContext'
import { mergeDemoDelta } from '../adapters/mergeDemoDelta.mjs'

const TERMINAL_STATES = new Set(['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'])

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
  const advisoryBusy = ref(false)
  const advisoryError = ref('')
  const currentAdvisory = ref(null)
  const decisionBusy = ref(false)
  const decisionError = ref('')
  const lastDecisionAck = ref(null)
  const scenarioTemplates = ref([])
  const taskPreview = ref(null)
  const tutorialGenerationPreset = ref(null)
  const tutorialRedConflictLocked = ref(false)
  const tutorialRewindLocked = ref(false)
  const tutorialMissionReactionsSuppressed = ref(false)
  const historyItems = ref([])
  const plannerOpen = ref(false)
  const historyOpen = ref(false)
  const replayBundle = ref(null)
  const selectedAirspaceId = ref('')
  const mapFollowingDeviceId = ref('')
  const airspaceActionBusy = ref(false)
  const lastAirspaceAction = ref(null)
  const latestEconomyTransaction = ref(null)
  const latestAirspaceWarning = ref(null)
  let eventSource = null
  let pollTimer = null

  const session = computed(() => context.mission.value.source.session)
  const hasSession = computed(() => Boolean(session.value?.id))
  const isTerminal = computed(() => TERMINAL_STATES.has(String(session.value?.status || '')))

  function applySnapshot(payload, { mergeEconomy = true } = {}) {
    if (!payload) return
    context.ingestSnapshot(payload)
    if (payload.session) {
      const nextScale = Number(payload.session.timeScale ?? 1)
      timeScale.value = nextScale
      if (nextScale > 0) lastActiveTimeScale.value = nextScale
    }
    if (Array.isArray(payload.advisories) && payload.advisories.length) currentAdvisory.value = payload.advisories.at(-1)
    if (Array.isArray(payload.decisions) && payload.decisions.length) lastDecisionAck.value = payload.decisions.at(-1)
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
    pollTimer = window.setInterval(() => refreshMission().catch(() => {}), 15000)
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
    if (type === 'advisory-delta') currentAdvisory.value = delta.advisory
    if (type === 'decision-ack') lastDecisionAck.value = delta.decision
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
    if (!session.value?.id || isTerminal.value) return
    eventSource = new EventSource(session.value.taskInstanceId ? runEventsUrl(session.value.id) : sessionEventsUrl(session.value.id), { withCredentials: true })
    eventSource.onopen = () => { notice.value = ''; stopFallbackPolling() }
    eventSource.addEventListener('snapshot', event => {
      notice.value = ''
      applySnapshot(JSON.parse(event.data))
    })
    ;['mission-delta', 'track-delta', 'signal-delta', 'command-ack', 'advisory-delta', 'decision-ack', 'airspace-delta', 'airspace-warning', 'economy-delta', 'rewind-checkpoint'].forEach(type => {
      eventSource.addEventListener(type, event => applyDelta(type, event))
    })
    eventSource.addEventListener('session-end', event => {
      applySnapshot(JSON.parse(event.data))
      eventSource?.close()
    })
    eventSource.onerror = () => {
      notice.value = '实时链路正在重连，已保留最近一次任务数据。'
      startFallbackPolling()
    }
  }

  async function initialize() {
    try {
      const [templateResult, historyResult] = await Promise.all([getScenarioTemplates(), getTaskHistory(20)])
      scenarioTemplates.value = templateResult?.items || []
      historyItems.value = historyResult?.items || []
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
      plannerOpen.value = true
      context.ingestSnapshot({ session: null, mission: taskPreview.value.plan, devices: previewDevices(taskPreview.value.plan) })
      return taskPreview.value
    } catch (error) {
      notice.value = error.message
      throw error
    } finally { busy.value = false }
  }

  async function startPreview() {
    if (!taskPreview.value?.taskId) return null
    busy.value = true
    notice.value = ''
    try {
      applySnapshot(await startTaskRun(taskPreview.value.taskId))
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
  function closePlanner() { plannerOpen.value = false }
  function closeHistory() { historyOpen.value = false }
  function setTutorialGenerationPreset(preset = null) {
    const seed = String(preset?.seed || '').trim()
    tutorialGenerationPreset.value = seed ? Object.freeze({ seed }) : null
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
      currentAdvisory.value = null
      lastDecisionAck.value = null
      signalCommandError.value = ''
      advisoryError.value = ''
      decisionError.value = ''
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
    context.clearFocus()
    context.returnToLive()
    taskPreview.value = null
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
    if (!session.value?.id) return
    try {
      applySnapshot(await stopSession(session.value.id))
      eventSource?.close()
    } catch (error) {
      notice.value = error.message
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

  async function generateSignalAdvisory(signalId, objective = 'BALANCED') {
    if (!session.value?.id || !signalId) return null
    advisoryBusy.value = true
    advisoryError.value = ''
    const advisoryId = globalThis.crypto?.randomUUID?.() || `advisory-${Date.now()}-${Math.random().toString(16).slice(2)}`
    try {
      currentAdvisory.value = await requestSignalAdvisory(session.value.id, signalId, advisoryId, objective)
      lastDecisionAck.value = null
      return currentAdvisory.value
    } catch (error) {
      advisoryError.value = error.message
      throw error
    } finally {
      advisoryBusy.value = false
    }
  }

  async function submitAdvisoryDecision(signalId, advisoryId, type, optionId, expectedAdvisoryStatus) {
    if (!session.value?.id || !signalId || !advisoryId || !optionId) return null
    decisionBusy.value = true
    decisionError.value = ''
    const decisionId = globalThis.crypto?.randomUUID?.() || `decision-${Date.now()}-${Math.random().toString(16).slice(2)}`
    try {
      lastDecisionAck.value = await postAdvisoryDecision(session.value.id, signalId, advisoryId, {
        decisionId, type, optionId, expectedAdvisoryStatus
      })
      return lastDecisionAck.value
    } catch (error) {
      decisionError.value = error.message
      throw error
    } finally {
      decisionBusy.value = false
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

  function dispose() {
    eventSource?.close()
    stopFallbackPolling()
    clearTutorialRedConflictLocked()
    clearTutorialRewindLocked()
    clearTutorialMissionReactionsSuppressed()
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
    advisoryBusy,
    advisoryError,
    currentAdvisory,
    decisionBusy,
    decisionError,
    lastDecisionAck,
    scenarioTemplates,
    taskPreview,
    tutorialGenerationPreset: readonly(tutorialGenerationPreset),
    tutorialRedConflictLocked: readonly(tutorialRedConflictLocked),
    tutorialRewindLocked: readonly(tutorialRewindLocked),
    tutorialMissionReactionsSuppressed: readonly(tutorialMissionReactionsSuppressed),
    historyItems,
    plannerOpen,
    historyOpen,
    replayBundle,
    selectedAirspaceId,
    mapFollowingDeviceId: readonly(mapFollowingDeviceId),
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
    generateSignalAdvisory,
    submitAdvisoryDecision,
    selectAirspace,
    closeAirspace,
    executeAirspaceAction,
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
      mediaSources: actor.mediaSources || [],
      longitude: Number(point[0]), latitude: Number(point[1]), altitude: actor.kind === 'UAV' ? Number(point[2] || 0) + 2 : Number(point[2] || 0),
      sensorData: { missionPhase: 'DOCKED', routeProgress: 0, routeDeviationMeters: 0, battery: actor.initialBattery || 100, linkQuality: 100, deliveryProgressPercent: 0 }
    }
  })
}
