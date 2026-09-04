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
export const getMission = id => request(`/api/demo/sessions/${encodeURIComponent(id)}/mission`)
export const changeSpeed = (id, timeScale) => request(`/api/demo/sessions/${encodeURIComponent(id)}/speed`, { method: 'PATCH', body: JSON.stringify({ timeScale }) })
export const restartSession = id => request(`/api/demo/sessions/${encodeURIComponent(id)}/restart`, { method: 'POST' })
export const stopSession = id => request(`/api/demo/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const sessionEventsUrl = id => `/api/demo/sessions/${encodeURIComponent(id)}/events`

