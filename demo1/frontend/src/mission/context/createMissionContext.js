import { computed, ref, shallowRef } from 'vue'
import { adaptCurrentDemoSnapshot } from '../adapters/logisticsMissionAdapter.mjs'
import { actorById, preferredMediaSource, signalById } from '../presentation/missionSelectors.mjs'
import { buildReplayMissionPresentation } from '../presentation/replayPresentation.mjs'

export function createMissionContext(initialSnapshot = null) {
  const rawSnapshot = shallowRef(null)
  const liveMission = shallowRef(adaptCurrentDemoSnapshot())
  const focusedActorId = ref('')
  const focusedSignalId = ref('')
  const timeCursor = ref(0)
  const timeMode = ref('LIVE')
  const viewMode = ref('OVERVIEW')
  const actionMode = ref('IDLE')

  const mission = computed(() => timeMode.value === 'REPLAY'
    ? buildReplayMissionPresentation(liveMission.value, timeCursor.value)
    : liveMission.value)
  const focusedActor = computed(() => actorById(mission.value, focusedActorId.value))
  const focusedSignal = computed(() => signalById(mission.value, focusedSignalId.value))
  const activePhase = computed(() => mission.value.phases.find(phase => phase.id === mission.value.activePhaseId) || null)
  const activeMission = computed(() => mission.value)
  const activeMediaSource = computed(() => preferredMediaSource(focusedActor.value))

  function ingestSnapshot(snapshot) {
    rawSnapshot.value = snapshot || null
    liveMission.value = adaptCurrentDemoSnapshot(snapshot || {})
    if (focusedActorId.value && !liveMission.value.actorsById[focusedActorId.value]) focusedActorId.value = ''
    if (focusedSignalId.value && !liveMission.value.signalsById[focusedSignalId.value]) focusedSignalId.value = ''
    if (timeMode.value === 'LIVE') timeCursor.value = liveMission.value.clock.progress
  }

  function focusActor(actorId) {
    const id = String(actorId || '')
    if (id && !mission.value.actorsById[id]) return false
    focusedActorId.value = id
    focusedSignalId.value = ''
    viewMode.value = id ? 'ACTOR_FOCUS' : 'OVERVIEW'
    actionMode.value = 'IDLE'
    return true
  }

  function focusSignal(signalId) {
    const id = String(signalId || '')
    const signal = mission.value.signalsById[id]
    if (!signal) return false
    focusedSignalId.value = id
    if (signal.actorIds[0]) focusedActorId.value = signal.actorIds[0]
    viewMode.value = 'SIGNAL_FOCUS'
    actionMode.value = signal.requiresAction ? 'ATTENTION' : 'IDLE'
    return true
  }

  function clearFocus() {
    focusedActorId.value = ''
    focusedSignalId.value = ''
    viewMode.value = 'OVERVIEW'
    actionMode.value = 'IDLE'
  }

  function setTimeCursor(value) {
    timeCursor.value = Math.max(0, Math.min(100, Number(value) || 0))
  }

  function enterReplay(value = liveMission.value.clock.progress) {
    timeMode.value = 'REPLAY'
    setTimeCursor(value)
  }

  function returnToLive() {
    timeMode.value = 'LIVE'
    timeCursor.value = liveMission.value.clock.progress
  }

  function openActionMode(reason = 'ATTENTION') { actionMode.value = String(reason || 'ATTENTION') }
  function closeActionMode() { actionMode.value = 'IDLE' }

  if (initialSnapshot) ingestSnapshot(initialSnapshot)

  return {
    rawSnapshot,
    liveMission,
    mission,
    activeMission,
    activePhase,
    focusedActorId,
    focusedSignalId,
    focusedActor,
    focusedSignal,
    activeMediaSource,
    timeCursor,
    timeMode,
    viewMode,
    actionMode,
    ingestSnapshot,
    focusActor,
    focusSignal,
    clearFocus,
    setTimeCursor,
    enterReplay,
    returnToLive,
    openActionMode,
    closeActionMode
  }
}
