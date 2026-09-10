import {
  FLEET_HUB_DEPENDENT_STEPS,
  FLEET_TUTORIAL_CHAPTER,
  PLANNER_DEPENDENT_STEPS,
  PROLOGUE_STEPS,
  RUN_DEPENDENT_STEPS,
  TUTORIAL_ATTENTION_VERSION,
  TUTORIAL_CHAPTER,
  getTutorialChapter
} from './tutorialScript.mjs'

export const TUTORIAL_PHASES = Object.freeze({
  CLOSED: 'CLOSED',
  INTRO: 'INTRO',
  DIALOGUE: 'DIALOGUE',
  TRANSITION_TO_ACTION: 'TRANSITION_TO_ACTION',
  ACTION: 'ACTION',
  ACTION_SUCCESS: 'ACTION_SUCCESS',
  TRANSITION_TO_DIALOGUE: 'TRANSITION_TO_DIALOGUE',
  COMPLETE: 'COMPLETE'
})

export function createTutorialState(overrides = {}) {
  return {
    phase: TUTORIAL_PHASES.CLOSED,
    stepIndex: 0,
    wrongAttempts: 0,
    waiting: false,
    ...overrides
  }
}

export function nextStepState(state, steps = PROLOGUE_STEPS) {
  const nextIndex = state.stepIndex + 1
  if (nextIndex >= steps.length) {
    return { ...state, phase: TUTORIAL_PHASES.COMPLETE, waiting: false, wrongAttempts: 0 }
  }
  const next = steps[nextIndex]
  return {
    ...state,
    stepIndex: nextIndex,
    phase: ['action', 'system'].includes(next.mode) ? TUTORIAL_PHASES.TRANSITION_TO_ACTION : TUTORIAL_PHASES.DIALOGUE,
    waiting: false,
    wrongAttempts: 0
  }
}

export function tutorialReducer(state, event, steps = PROLOGUE_STEPS) {
  switch (event.type) {
    case 'START':
      return createTutorialState({ phase: TUTORIAL_PHASES.INTRO, stepIndex: 0 })
    case 'RESUME': {
      const index = Math.max(0, steps.findIndex(step => step.id === event.stepId))
      const step = steps[index]
      return createTutorialState({ stepIndex: index, phase: ['action', 'system'].includes(step?.mode) ? TUTORIAL_PHASES.ACTION : TUTORIAL_PHASES.DIALOGUE })
    }
    case 'INTRO_FINISHED':
      return { ...state, phase: TUTORIAL_PHASES.DIALOGUE }
    case 'ADVANCE':
      if (state.phase !== TUTORIAL_PHASES.DIALOGUE) return state
      return nextStepState(state, steps)
    case 'ACTION_READY':
      if (state.phase !== TUTORIAL_PHASES.TRANSITION_TO_ACTION) return state
      return { ...state, phase: TUTORIAL_PHASES.ACTION }
    case 'ACTION_WAIT':
      if (state.phase !== TUTORIAL_PHASES.ACTION) return state
      return { ...state, waiting: true }
    case 'ACTION_RETRY':
      if (state.phase !== TUTORIAL_PHASES.ACTION) return state
      return { ...state, waiting: false }
    case 'WRONG_INTERACTION':
      if (state.phase !== TUTORIAL_PHASES.ACTION) return state
      return { ...state, wrongAttempts: state.wrongAttempts + 1 }
    case 'ACTION_COMPLETED':
      if (state.phase !== TUTORIAL_PHASES.ACTION) return state
      return { ...state, phase: TUTORIAL_PHASES.ACTION_SUCCESS, waiting: false }
    case 'SUCCESS_FINISHED':
      if (state.phase !== TUTORIAL_PHASES.ACTION_SUCCESS) return state
      {
        const next = nextStepState(state, steps)
        if (next.phase === TUTORIAL_PHASES.COMPLETE) return next
        return {
          ...next,
          phase: [TUTORIAL_PHASES.TRANSITION_TO_ACTION, TUTORIAL_PHASES.ACTION].includes(next.phase)
            ? TUTORIAL_PHASES.TRANSITION_TO_ACTION
            : TUTORIAL_PHASES.TRANSITION_TO_DIALOGUE
        }
      }
    case 'DIALOGUE_READY':
      if (state.phase !== TUTORIAL_PHASES.TRANSITION_TO_DIALOGUE) return state
      return { ...state, phase: TUTORIAL_PHASES.DIALOGUE }
    case 'CLOSE':
      return createTutorialState()
    default:
      return state
  }
}

export function createProgressRecord(stepId, status = 'in_progress', now = Date.now(), chapterId = TUTORIAL_CHAPTER, context = {}) {
  const chapter = getTutorialChapter(chapterId)
  const optional = Object.fromEntries(
    ['taskId', 'runId', 'redVolumeId', 'redDiamondId', 'pausedByTutorial', 'resumeTimeScale']
      .filter(key => context[key] !== undefined && context[key] !== null && context[key] !== '')
      .map(key => [key, context[key]])
  )
  return { version: chapter.version, chapter: chapter.id, stepId, status, updatedAt: now, ...optional }
}

export function parseProgressRecord(raw, chapterId = TUTORIAL_CHAPTER) {
  if (!raw) return null
  try {
    const chapter = getTutorialChapter(chapterId)
    const record = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (record?.version !== chapter.version || record?.chapter !== chapter.id) return null
    if (!['in_progress', 'completed', 'skipped'].includes(record.status)) return null
    return record
  } catch {
    return null
  }
}

export function resolveResumeStep(record, { plannerOpen = false, fleetHubOpen = false, chapterId = TUTORIAL_CHAPTER, activeRunId = '' } = {}) {
  if (!record || record.status !== 'in_progress') return null
  const chapter = getTutorialChapter(chapterId)
  const exists = chapter.steps.some(step => step.id === record.stepId)
  const stepId = exists ? record.stepId : chapter.steps[0].id
  if (chapter.id === TUTORIAL_CHAPTER && stepId === 'A04' && record.runId) {
    if (String(record.runId) === String(activeRunId || '')) return stepId
    return activeRunId ? null : 'A01'
  }
  if (chapter.id === TUTORIAL_CHAPTER && RUN_DEPENDENT_STEPS.has(stepId)) {
    if (record.runId && String(record.runId) === String(activeRunId || '')) return stepId
    return activeRunId ? null : 'A01'
  }
  if (chapter.id === TUTORIAL_CHAPTER && !plannerOpen && PLANNER_DEPENDENT_STEPS.has(stepId)) return 'A01'
  if (chapter.id === FLEET_TUTORIAL_CHAPTER && !fleetHubOpen && FLEET_HUB_DEPENDENT_STEPS.has(stepId)) return 'F01-A01'
  return stepId
}

export function tutorialChapterUnlocked(chapterId, statuses = {}) {
  if (chapterId === TUTORIAL_CHAPTER) return true
  if (chapterId === FLEET_TUTORIAL_CHAPTER) return ['in_progress', 'completed', 'skipped'].includes(statuses[FLEET_TUTORIAL_CHAPTER])
    || ['completed', 'skipped'].includes(statuses[TUTORIAL_CHAPTER])
  return false
}

export function canReplayTutorial({ hasSession = false, isTerminal = false, requestedRunId = '', activeRunId = '' } = {}) {
  return !hasSession || isTerminal || Boolean(requestedRunId && String(requestedRunId) === String(activeRunId || ''))
}

export function canAutoResumeTutorial(record, { activeRunId = '', isTerminal = false } = {}) {
  return Boolean(
    record?.status === 'in_progress'
    && record.runId
    && activeRunId
    && !isTerminal
    && String(record.runId) === String(activeRunId)
  )
}

export function createManualAttentionRecord(now = Date.now()) {
  return { version: TUTORIAL_ATTENTION_VERSION, seen: true, updatedAt: now }
}

export function parseManualAttentionRecord(raw) {
  if (!raw) return null
  try {
    const record = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (record?.version !== TUTORIAL_ATTENTION_VERSION || record?.seen !== true) return null
    return record
  } catch {
    return null
  }
}
