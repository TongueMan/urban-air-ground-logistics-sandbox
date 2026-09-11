import {
  normalizeMissionPhaseId,
  normalizePhaseDefinitions,
  phaseProgress,
  phaseStateAtProgress
} from '../domain/phaseCatalog.mjs'
import { logisticsPresentationText } from '../presentation/logisticsVocabulary.mjs'

const ACTOR_TYPE_PRESENTATION = Object.freeze({
  ground_vehicle: { kind: 'VEHICLE', role: 'MOBILE_BASE', label: '城市配送车', capabilities: ['MOBILITY', 'UAV_CARRIER'] },
  smart_drone: { kind: 'UAV', role: 'AIR_COURIER', label: '无人机', capabilities: ['FLIGHT', 'DELIVERY', 'TELEMETRY'] }
})

const SIGNAL_PRESENTATION = Object.freeze({
  ROAD_OBSTACLE: { severity: 'WARNING', requiresAction: true },
  LINK_WEAK: { severity: 'WARNING', requiresAction: false },
  COMPLETE: { severity: 'INFO', requiresAction: false },
  TAKEOFF: { severity: 'INFO', requiresAction: false },
  DELIVERY_START: { severity: 'INFO', requiresAction: false },
  RETURN: { severity: 'INFO', requiresAction: false },
  DOCKED: { severity: 'INFO', requiresAction: false }
})

function clampPercent(value) {
  return Math.max(0, Math.min(100, Number(value) || 0))
}

export function currentDemoActorActivity(kind, phaseId, missionStatus) {
  if (missionStatus !== 'RUNNING') return missionStatus === 'COMPLETED' ? 'COMPLETE' : 'STANDBY'
  if (kind === 'UAV') return ({ DEPLOY: 'ON_CARRIER', TAKEOFF: 'LAUNCHING', DELIVERY: 'DELIVERING', RETURN: 'RETURNING', RECOVERY: 'DOCKED' })[phaseId] || 'ACTIVE'
  return ({ DEPLOY: 'MOVING', TAKEOFF: 'DEPLOY_SUPPORT', DELIVERY: 'GROUND_SUPPORT', RETURN: 'RENDEZVOUS', RECOVERY: 'RECOVERING' })[phaseId] || 'ACTIVE'
}

function normalizeActor(device = {}, activePhaseId, missionStatus) {
  const type = ACTOR_TYPE_PRESENTATION[String(device.deviceType || '').toLowerCase()] || {
    kind: 'UNKNOWN', role: 'UNKNOWN', label: '设备', capabilities: ['TELEMETRY']
  }
  const serverKind = String(device.actorKind || '').toUpperCase()
  const serverRole = String(device.actorRole || '').toUpperCase()
  const telemetry = { ...(device.sensorData || {}) }
  const operationalActivity = telemetry.trafficRuleState === 'WAITING_FOR_UAV'
    ? 'RENDEZVOUS_WAIT'
    : telemetry.trafficRuleState === 'WAITING_FOR_SIGNAL' ? 'SIGNAL_WAIT' : null
  return {
    id: String(device.deviceId || ''),
    name: logisticsPresentationText(device.deviceName || device.deviceId || type.label),
    kind: serverKind || type.kind,
    role: serverRole || type.role,
    deviceType: String(device.deviceType || ''),
    activity: operationalActivity || currentDemoActorActivity(serverKind || type.kind, activePhaseId || normalizeMissionPhaseId(telemetry.missionPhase), missionStatus),
    capabilities: Array.isArray(device.capabilities) ? device.capabilities.map(String) : [...type.capabilities],
    position: {
      longitude: Number(device.longitude),
      latitude: Number(device.latitude),
      altitude: Number(device.altitude || 0)
    },
    heading: Number(telemetry.direction || 0),
    telemetry,
    formationId: device.formationId ? String(device.formationId) : null,
    assignmentId: device.assignmentId ? String(device.assignmentId) : null,
    commandCapabilities: Array.isArray(device.commandCapabilities) ? device.commandCapabilities.map(String) : [],
    commandTransport: device.commandTransport ? { ...device.commandTransport } : { status: 'UNAVAILABLE', reason: '当前 API 未声明设备命令传输能力' },
    source: 'CURRENT_DEMO_API'
  }
}

function normalizeExplicitFormations(rawFormations = [], actorsById) {
  return rawFormations.map((formation, index) => ({
    id: String(formation.id || `formation-${index + 1}`),
    label: String(formation.label || `协同编组 ${index + 1}`),
    leaderActorId: formation.leaderActorId ? String(formation.leaderActorId) : null,
    status: String(formation.status || 'ACTIVE'),
    members: (Array.isArray(formation.members) ? formation.members : []).map((member, memberIndex) => ({
      actorId: String(member.actorId || ''),
      role: String(member.role || 'MEMBER'),
      slot: Number(member.slot ?? memberIndex)
    })).filter(member => member.actorId)
  })).map(formation => {
    formation.members.forEach(member => {
      const actor = actorsById[member.actorId]
      if (actor) actor.formationId = formation.id
    })
    return formation
  })
}

function normalizeFormations(rawPairs = [], actorsById) {
  const grouped = new Map()
  rawPairs.forEach((pair, pairIndex) => {
    const vehicleId = String(pair.vehicleId || '')
    const droneId = String(pair.droneId || '')
    if (!vehicleId && !droneId) return
    const key = vehicleId || `formation-${pairIndex + 1}`
    if (!grouped.has(key)) grouped.set(key, {
      id: `formation-${key}`,
      label: String(pair.label || `协同编组 ${grouped.size + 1}`),
      leaderActorId: vehicleId || null,
      status: 'ACTIVE',
      members: []
    })
    const formation = grouped.get(key)
    if (vehicleId && !formation.members.some(member => member.actorId === vehicleId)) {
      formation.members.push({ actorId: vehicleId, role: 'MOBILE_BASE', slot: 0 })
    }
    if (droneId && !formation.members.some(member => member.actorId === droneId)) {
      formation.members.push({ actorId: droneId, role: 'AIR_COURIER', slot: Number(pair.slot || formation.members.length) })
    }
  })

  const formations = Array.from(grouped.values())
  formations.forEach(formation => formation.members.forEach(member => {
    const actor = actorsById[member.actorId]
    if (actor) actor.formationId = formation.id
  }))
  return formations
}

function signalLocation(rawMission, event, actor) {
  const route = (rawMission.routes || []).find(item => String(item.deviceId) === String(event.deviceId))
  const points = Array.isArray(route?.actualPoints) ? route.actualPoints : []
  const target = points.find(point => Number(point?.metrics?.routeProgress) >= Number(event.progress || 0))
  if (target) return { longitude: Number(target.longitude), latitude: Number(target.latitude), altitude: Number(target.altitude || 0), derived: true }
  return actor ? { ...actor.position, derived: true } : null
}

function normalizeSignal(rawMission, event, index, actorsById, missionProgress) {
  const type = String(event.type || 'EVENT').toUpperCase()
  const presentation = SIGNAL_PRESENTATION[type] || { severity: 'NOTICE', requiresAction: false }
  const occurred = event.reached === true || missionProgress >= Number(event.progress || 0)
  const actorId = event.deviceId ? String(event.deviceId) : null
  const actor = actorId ? actorsById[actorId] : null
  const actions = []
  if (actor) actions.push({ id: 'focus-actor', label: '定位设备', kind: 'FOCUS_ACTOR', availability: 'AVAILABLE', requiresConfirmation: false })
  return {
    id: `demo-signal-${index + 1}-${type.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    type,
    label: logisticsPresentationText(event.label || type),
    severity: presentation.severity,
    confidence: Number.isFinite(Number(event.confidence)) ? Number(event.confidence) : null,
    status: occurred ? 'DETECTED' : 'SCHEDULED',
    occurredAt: null,
    progress: clampPercent(event.progress),
    location: signalLocation(rawMission, event, actor),
    actorIds: actorId ? [actorId] : [],
    requiresAction: occurred && presentation.requiresAction,
    actionRequiredWhenDetected: presentation.requiresAction,
    actions,
    source: 'SIMULATION_DEFINITION',
    derived: true,
    raw: event
  }
}

const WORKFLOW_ACTION_LABELS = Object.freeze({
  ACKNOWLEDGE: '确认异常',
  BEGIN_INVESTIGATION: '开始调查',
  RESOLVE: '标记解决',
  IGNORE: '忽略异常'
})

function normalizeServerSignal(rawMission, signal, actorsById) {
  const actorIds = Array.isArray(signal.actorIds) ? signal.actorIds.map(String) : []
  const actor = actorIds[0] ? actorsById[actorIds[0]] : null
  const sourceEvent = (rawMission.events || []).find(event => String(event.id || '') === String(signal.key || ''))
    || (rawMission.events || []).find(event => String(event.type || '') === String(signal.type || '') && Number(event.progress || 0) === Number(signal.progress || 0))
    || signal
  const actions = []
  if (actor) actions.push({ id: 'focus-actor', label: '定位设备', kind: 'FOCUS_ACTOR', availability: 'AVAILABLE', requiresConfirmation: false })
  ;(Array.isArray(signal.allowedActions) ? signal.allowedActions : []).forEach(type => actions.push({
    id: `workflow-${String(type).toLowerCase()}`,
    label: WORKFLOW_ACTION_LABELS[type] || type,
    kind: 'WORKFLOW_COMMAND',
    commandType: type,
    availability: 'AVAILABLE',
    requiresConfirmation: ['RESOLVE', 'IGNORE'].includes(type)
  }))
  const status = String(signal.status || 'SCHEDULED').toUpperCase()
  return {
    id: String(signal.id || ''),
    key: String(signal.key || ''),
    type: String(signal.type || 'EVENT').toUpperCase(),
    label: logisticsPresentationText(signal.label || signal.type || '任务事件'),
    severity: String(signal.severity || 'NOTICE').toUpperCase(),
    confidence: Number.isFinite(Number(signal.confidence)) ? Number(signal.confidence) : null,
    status,
    occurredAt: signal.detectedAt || null,
    updatedAt: signal.updatedAt || null,
    statusHistory: Array.isArray(signal.statusHistory) ? signal.statusHistory.map(item => ({ ...item })) : [],
    progress: clampPercent(signal.progress),
    location: signal.location ? { ...signal.location, derived: false } : signalLocation(rawMission, sourceEvent, actor),
    actorIds,
    requiresAction: Boolean(signal.requiresAction),
    actionRequiredWhenDetected: Boolean(sourceEvent.requiresAction ?? signal.requiresAction),
    actions,
    source: String(signal.source || 'MISSION_SERVICE'),
    derived: false,
    serverManaged: true,
    raw: signal
  }
}

export function adaptCurrentDemoSnapshot(snapshot = {}) {
  const rawSession = snapshot.session || null
  const rawMission = snapshot.mission || {}
  const rawDevices = Array.isArray(snapshot.devices) ? snapshot.devices : []
  const status = String(rawSession?.status || rawMission.status || rawMission.state || 'STANDBY').toUpperCase()
  const progress = clampPercent(rawMission.progress ?? rawSession?.progress)
  const phaseIsActive = !['STANDBY', 'QUEUED'].includes(status)
  const phaseDefinitions = normalizePhaseDefinitions(rawMission)
  const rawPhaseSourceId = String(rawMission.missionPhase || rawSession?.missionPhase || '').toUpperCase()
  const rawPhase = phaseDefinitions.find(phase => phase.sourceId === rawPhaseSourceId)
  const rawPhaseMatchesProgress = rawPhase && progress >= rawPhase.range[0]
    && (progress < rawPhase.range[1] || (progress === 100 && rawPhase.range[1] === 100))
  const progressPhase = phaseDefinitions.find(phase => progress >= phase.range[0] && progress < phase.range[1])
    || phaseDefinitions.at(-1)
  const activePhaseId = phaseIsActive
    ? (rawPhaseMatchesProgress ? rawPhase.id : progressPhase?.id || normalizeMissionPhaseId(rawPhaseSourceId))
    : null
  const phases = phaseDefinitions.map(phase => ({
    ...phase,
    state: phaseStateAtProgress(phase, activePhaseId, progress),
    progress: phaseProgress(phase, progress)
  }))
  const actors = rawDevices.map(device => normalizeActor(device, activePhaseId, status)).filter(actor => actor.id)
  const actorsById = Object.fromEntries(actors.map(actor => [actor.id, actor]))
  const formations = Array.isArray(rawMission.formations) && rawMission.formations.length
    ? normalizeExplicitFormations(rawMission.formations, actorsById)
    : normalizeFormations(Array.isArray(rawMission.pairs) ? rawMission.pairs : [], actorsById)
  const assignments = (Array.isArray(rawMission.assignments) ? rawMission.assignments : []).map(assignment => ({
    id: String(assignment.id || ''), actorId: String(assignment.actorId || ''), role: String(assignment.role || ''),
    routeId: assignment.routeId ? String(assignment.routeId) : null, status: String(assignment.status || 'ASSIGNED')
  })).filter(assignment => assignment.id && assignment.actorId)
  const signals = Array.isArray(snapshot.signals)
    ? snapshot.signals.map(signal => normalizeServerSignal(rawMission, signal, actorsById)).filter(signal => signal.id)
    : (Array.isArray(rawMission.events) ? rawMission.events : [])
        .map((event, index) => normalizeSignal(rawMission, event, index, actorsById, progress))
  const durationSeconds = Number(rawMission.plannedDurationSeconds || rawMission.estimatedDurationSeconds || rawMission.durationSeconds || 180)
  const elapsedSeconds = rawSession?.simulationElapsedMs == null
    ? durationSeconds * progress / 100
    : Math.max(0, Number(rawSession.simulationElapsedMs) / 1000)

  return {
    id: String(rawSession?.id || rawMission.simulationId || rawMission.scenarioKey || 'standby-mission'),
    definitionId: String(rawMission.scenarioKey || 'urban-logistics-operation'),
    definitionVersion: String(rawSession?.definitionVersion || rawMission.version || rawMission.routeVersion || 'unknown'),
    name: logisticsPresentationText(rawMission.name || '双编组空地协同配送'),
    status,
    progress,
    activePhaseId,
    phases,
    actors,
    actorsById,
    formations,
    assignments,
    groundVehicle: rawMission.groundVehicle || null,
    airVehicle: rawMission.airVehicle || null,
    terminalReason: rawMission.terminalReason || rawSession?.terminalReason || null,
    unassignedActorIds: actors.filter(actor => !actor.formationId).map(actor => actor.id),
    signals,
    signalsById: Object.fromEntries(signals.map(signal => [signal.id, signal])),
    clock: {
      mode: 'LIVE',
      progress,
      elapsedSeconds,
      durationSeconds,
      startedAt: rawSession?.startedAt || null
    },
    world: {
      coordinateSystem: String(rawMission.coordinateSystem || 'BD09LL'),
      routes: Array.isArray(rawMission.routes) ? rawMission.routes : [],
      serviceRadiusMeters: Number(rawMission.serviceRadiusMeters || 250)
    },
    source: 'CURRENT_DEMO_ADAPTER',
    source: { session: rawSession, mission: rawMission, devices: rawDevices }
  }
}
