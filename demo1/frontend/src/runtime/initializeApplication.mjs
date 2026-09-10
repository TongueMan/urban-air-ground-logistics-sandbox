async function settle(task) {
  try {
    return { status: 'fulfilled', value: await task() }
  } catch (reason) {
    return { status: 'rejected', reason }
  }
}

/**
 * Establish the visitor cookie through the fleet endpoint before any other
 * visitor-scoped request is sent. Parallel first requests can otherwise be
 * assigned different visitor identities by the server.
 */
export async function initializeApplication(runtime, fleetRuntime) {
  const fleet = await settle(() => fleetRuntime.initialize())
  const mission = await settle(() => runtime.initialize())
  return { fleet, mission }
}
