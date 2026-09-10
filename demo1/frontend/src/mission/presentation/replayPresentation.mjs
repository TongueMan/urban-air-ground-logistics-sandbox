import { missionViewState } from '../../utils/missionMotion.mjs'
import { currentDemoActorActivity } from '../adapters/logisticsMissionAdapter.mjs'
import { normalizeMissionPhaseId, phaseProgress, phaseStateAtProgress } from '../domain/phaseCatalog.mjs'

export function buildReplayMissionPresentation(liveMission, cursor) {
  if (!liveMission?.source) return liveMission
  const percent = Math.max(0, Math.min(100, Number(cursor) || 0))
  const timeline = liveMission.source.mission?.timeline || {}
  const liveProgress = Math.max(.1, Number(timeline.liveProgress ?? liveMission.progress ?? 100))
  const liveTimeMs = Number(timeline.liveSimulationTimeMs ?? liveMission.source.mission?.replayDurationMs ?? liveMission.clock.durationSeconds * 1000)
  const replayTimeMs = liveTimeMs * Math.min(liveProgress, percent) / liveProgress
  const view = missionViewState(liveMission.source.mission, liveMission.source.devices, percent, { forceReplay: true, replayTimeMs })
  const activePhaseId = normalizeMissionPhaseId(view.phase)
  const phases = liveMission.phases.map(phase => ({
    ...phase,
    state: phaseStateAtProgress(phase, activePhaseId, view.progress),
    progress: phaseProgress(phase, view.progress)
  }))
  const actors = liveMission.actors.map(actor => {
    const telemetry = view.metricsById.get(actor.id) || actor.telemetry
    return {
      ...actor,
      telemetry,
      heading: Number(telemetry?.direction ?? actor.heading),
      activity: currentDemoActorActivity(actor.kind, normalizeMissionPhaseId(telemetry?.missionPhase || activePhaseId), 'RUNNING')
    }
  })
  const signals = liveMission.signals.map(signal => {
    const detected = signal.progress <= view.progress
    const historicalStatus = signal.serverManaged
      ? [...(signal.statusHistory || [])].filter(item => Number(item.progress) <= view.progress).at(-1)?.status
      : null
    const replayStatus = detected ? (historicalStatus || (signal.serverManaged ? signal.status : 'DETECTED')) : 'SCHEDULED'
    return {
      ...signal,
      status: replayStatus,
      requiresAction: detected && signal.actionRequiredWhenDetected && !['RESOLVED', 'IGNORED'].includes(replayStatus)
    }
  })
  return {
    ...liveMission,
    progress: view.progress,
    activePhaseId,
    phases,
    actors,
    actorsById: Object.fromEntries(actors.map(actor => [actor.id, actor])),
    signals,
    signalsById: Object.fromEntries(signals.map(signal => [signal.id, signal])),
    clock: {
      ...liveMission.clock,
      mode: 'REPLAY',
      progress: view.progress,
      elapsedSeconds: replayTimeMs / 1000
    },
    presentationMode: 'REPLAY'
  }
}
