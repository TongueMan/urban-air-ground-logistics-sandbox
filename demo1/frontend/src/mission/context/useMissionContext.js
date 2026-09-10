import { inject, provide } from 'vue'

export const MISSION_CONTEXT_KEY = Symbol('spatial-mission-context')

export function provideMissionContext(context) {
  provide(MISSION_CONTEXT_KEY, context)
  return context
}
export function useMissionContext() {
  const context = inject(MISSION_CONTEXT_KEY, null)
  if (!context) throw new Error('MissionContext is not available in this component tree')
  return context
}
