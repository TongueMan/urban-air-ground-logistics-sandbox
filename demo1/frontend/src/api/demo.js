async function request(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || `请求失败 (${response.status})`)
  return body
}

export const getCurrentSession = () => request('/api/demo/sessions/current')
export const createSession = () => request('/api/demo/sessions', { method: 'POST' })
export const getScenarioTemplates = () => request('/api/demo/scenario-templates')
export const generateTaskInstance = payload => request('/api/demo/task-instances', { method: 'POST', body: JSON.stringify(payload) })
export const getTaskInstance = id => request(`/api/demo/task-instances/${encodeURIComponent(id)}`)
export const startTaskRun = id => request(`/api/demo/task-instances/${encodeURIComponent(id)}/runs`, { method: 'POST' })
export const getTaskHistory = (limit = 20) => request(`/api/demo/task-instances?limit=${encodeURIComponent(limit)}`)
export const getRun = id => request(`/api/demo/runs/${encodeURIComponent(id)}`)
export const getRunReplay = id => request(`/api/demo/runs/${encodeURIComponent(id)}/replay`)
export const executeAirspaceAction = (runId, volumeId, actionType) => request(
  `/api/demo/runs/${encodeURIComponent(runId)}/airspace-conflicts/${encodeURIComponent(volumeId)}/actions`,
  { method: 'POST', body: JSON.stringify({ actionType }) }
)
export const restoreRewindCheckpoint = (runId, checkpointId, payload) => request(
  `/api/demo/runs/${encodeURIComponent(runId)}/rewind-checkpoints/${encodeURIComponent(checkpointId)}/restore`,
  { method: 'POST', body: JSON.stringify(payload) }
)
export const getMission = id => request(`/api/demo/sessions/${encodeURIComponent(id)}/mission`)
export const getMissionHistory = (id, { from, to, actorId } = {}) => {
  const query = new URLSearchParams()
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  if (actorId) query.set('actorId', actorId)
  const suffix = query.size ? `?${query}` : ''
  return request(`/api/demo/sessions/${encodeURIComponent(id)}/history${suffix}`)
}
export const changeSpeed = (id, timeScale) => request(`/api/demo/sessions/${encodeURIComponent(id)}/speed`, { method: 'PATCH', body: JSON.stringify({ timeScale }) })
export const restartSession = id => request(`/api/demo/sessions/${encodeURIComponent(id)}/restart`, { method: 'POST' })
export const stopSession = id => request(`/api/demo/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const executeSignalCommand = (sessionId, signalId, command) => request(
  `/api/demo/sessions/${encodeURIComponent(sessionId)}/signals/${encodeURIComponent(signalId)}/commands`,
  { method: 'POST', body: JSON.stringify(command) }
)
export const sessionEventsUrl = id => `/api/demo/sessions/${encodeURIComponent(id)}/events`
export const runEventsUrl = id => `/api/demo/runs/${encodeURIComponent(id)}/events`
export const getFleet = () => request('/api/demo/fleet')
export const purchaseFleetAsset = payload => request('/api/demo/fleet/purchases', {
  method: 'POST', body: JSON.stringify(payload)
})
export const sellFleetAsset = payload => request('/api/demo/fleet/sales', {
  method: 'POST', body: JSON.stringify(payload)
})
export const changeFleetAssetStatus = (assetId, payload) => request(
  `/api/demo/fleet/assets/${encodeURIComponent(assetId)}/status`,
  { method: 'PATCH', body: JSON.stringify(payload) }
)
