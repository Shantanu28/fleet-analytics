import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { SessionProvider, useSession } from '../auth/session'

/**
 * Drives the session provider directly. The login form disables its button while a request is in
 * flight, so two overlapping sign-ins cannot be produced through the form — but the provider is a
 * public contract that must still hold, and these are the cases that prove it does.
 */
function SessionHarness() {
  const { session, signIn, signOut } = useSession()
  const attempt = (username: string) => () => { void signIn(username, 'pw').catch(() => {}) }
  return (
    <div>
      <button type="button" onClick={attempt('acme.admin')}>attempt acme</button>
      <button type="button" onClick={attempt('beacon.admin')}>attempt beacon</button>
      <button type="button" onClick={signOut}>harness sign out</button>
      <span data-testid="signed-in-as">{session ? session.displayName : 'nobody'}</span>
    </div>
  )
}

function renderHarness(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  render(
    <QueryClientProvider client={client}>
      <SessionProvider><SessionHarness /></SessionProvider>
    </QueryClientProvider>,
  )
  return client
}

/**
 * Holds one `cancelQueries` call open. Sign-in awaits cache cancellation after the HTTP response
 * has already landed, so that await is a second window in which a sign-out or a newer sign-in can
 * happen — and the completing attempt must still not win.
 */
function gateCancellation(client: QueryClient, holdCall = 1) {
  const gate = deferred<void>()
  const original = client.cancelQueries.bind(client)
  let calls = 0
  vi.spyOn(client, 'cancelQueries').mockImplementation(async (filters?: Parameters<QueryClient['cancelQueries']>[0]) => {
    calls += 1
    if (calls === holdCall) await gate.promise
    return original(filters)
  })
  return gate
}

const signedInAs = () => screen.getByTestId('signed-in-as').textContent

/** A response that resolves only when the test releases it. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

const ACME_ADMIN = {
  accessToken: 'tok-acme', expiresInSeconds: 900,
  userId: 'u-acme', organisationId: 'org-acme', displayName: 'Acme Admin', role: 'ADMIN',
}
/** A different person, same role, different tenant — so isolation cannot pass on role alone. */
const BEACON_ADMIN = {
  accessToken: 'tok-beacon', expiresInSeconds: 900,
  userId: 'u-beacon', organisationId: 'org-beacon', displayName: 'Beacon Admin', role: 'ADMIN',
}

const acmeContext = {
  organisationName: 'Acme Engineering', role: 'ADMIN',
  teams: [{ id: 'acme-t1', name: 'Acme Platform' }],
  repositories: [{ id: 'acme-r1', name: 'acme-api' }],
  licensedSeats: 2,
  coverage: { dataAvailableFrom: '2026-01-01T00:00:00Z', dataThrough: '2026-03-01T00:00:00Z', revision: 'rev-acme' },
}

/** Entirely its own teams, repositories and seat count — no fields borrowed from Acme. */
const beaconContext = {
  organisationName: 'Beacon Labs', role: 'ADMIN',
  teams: [{ id: 'beacon-t1', name: 'Beacon Core' }],
  repositories: [{ id: 'beacon-r1', name: 'beacon-web' }],
  licensedSeats: 1,
  coverage: { dataAvailableFrom: '2026-02-01T00:00:00Z', dataThrough: '2026-04-01T00:00:00Z', revision: 'rev-beacon' },
}

const unauthorised = jsonResponse(
  { type: 'urn:fleet:problem:unauthenticated', status: 401, detail: 'Authentication is required.' }, 401)

beforeEach(() => { vi.stubGlobal('fetch', vi.fn()) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers() })

async function signIn(user: ReturnType<typeof userEvent.setup>, username: string, password: string) {
  await user.clear(screen.getByLabelText(/username/i))
  await user.type(screen.getByLabelText(/username/i), username)
  await user.clear(screen.getByLabelText(/password/i))
  await user.type(screen.getByLabelText(/password/i), password)
  await user.click(screen.getByRole('button', { name: /sign in/i }))
}

describe('session', () => {
  it('signs in and shows the authenticated organisation', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')

    expect(await screen.findByText('Acme Engineering')).toBeVisible()
    expect(screen.getByText(/2 licensed seats/i)).toBeVisible()
  })

  /** AC-01.3: the cutoff is dataThrough − 1 day, never dataThrough itself. */
  it('states the reporting cutoff as the last complete UTC day', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')

    expect(await screen.findByText(/data complete through 2026-02-28/i)).toBeVisible()
    expect(screen.queryByText(/2026-03-01/)).not.toBeInTheDocument()
  })

  it('shows a sanitised message and no token on invalid credentials', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(
      { type: 'urn:fleet:problem:invalid-credentials', status: 401, detail: 'Invalid username or password.' }, 401))

    render(<App />)
    await signIn(user, 'acme.admin', 'wrong')

    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid username or password/i)
    expect(screen.getByLabelText(/username/i)).toBeVisible()
  })

  it('does not retry an authentication failure', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValue(unauthorised)

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByLabelText(/username/i)                   // the 401 returned us to login

    const callsSoFar = vi.mocked(fetch).mock.calls.length
    expect(callsSoFar).toBe(2)                                  // login + one context attempt

    // A retry would be scheduled on a timer. Switching to a controlled clock only now — after the
    // DOM has settled — lets us run well past any backoff without polling a stubbed timer.
    vi.useFakeTimers()
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    vi.useRealTimers()

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(callsSoFar)
  })

  it('logout clears protected data and states that the token is not revoked', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    expect(screen.getByText(/does not revoke/i)).toBeVisible()

    await user.click(screen.getByRole('button', { name: /sign out/i }))

    expect(await screen.findByLabelText(/username/i)).toBeVisible()
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
  })

  it('a context response arriving after logout never repopulates the view', async () => {
    const user = userEvent.setup()
    const late = deferred<Response>()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockReturnValueOnce(late.promise as unknown as Promise<Response>)

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await user.click(await screen.findByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)

    await act(async () => { late.resolve(jsonResponse(acmeContext)) })

    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
    expect(screen.getByLabelText(/username/i)).toBeVisible()
  })

  /** Two different people with the SAME role: isolation must not rest on role alone. */
  it('switching to a different tenant with the same role shows no previous data', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))
      .mockResolvedValueOnce(jsonResponse(BEACON_ADMIN))
      .mockResolvedValueOnce(jsonResponse(beaconContext))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)
    await signIn(user, 'beacon.admin', 'secret-b')

    expect(await screen.findByText('Beacon Labs')).toBeVisible()
    expect(screen.getByText('Beacon Core')).toBeVisible()
    expect(screen.getByText('beacon-web')).toBeVisible()
    expect(screen.getByText(/1 licensed seat$/i)).toBeVisible()
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
    expect(screen.queryByText('Acme Platform')).not.toBeInTheDocument()
    expect(screen.queryByText('acme-api')).not.toBeInTheDocument()
  })

  /** The same person signing back in must not see the previous session's cached answer. */
  it('a second session for the same user and role refetches rather than reusing the cache', async () => {
    const user = userEvent.setup()
    const changed = { ...acmeContext, licensedSeats: 7 }
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(changed))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    expect(await screen.findByText(/2 licensed seats/i)).toBeVisible()

    await user.click(screen.getByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)
    await signIn(user, 'acme.admin', 'secret-a')

    expect(await screen.findByText(/7 licensed seats/i)).toBeVisible()
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(4)
  })

  /** A slow login that resolves after logout must not silently restore a session. */
  it('a login completing after sign-out does not sign the user in', async () => {
    const user = userEvent.setup()
    const slowLogin = deferred<Response>()
    vi.mocked(fetch).mockReturnValueOnce(slowLogin.promise as unknown as Promise<Response>)

    renderHarness()
    await user.click(screen.getByRole('button', { name: /attempt acme/i }))
    await user.click(screen.getByRole('button', { name: /harness sign out/i }))

    await act(async () => { slowLogin.resolve(jsonResponse(ACME_ADMIN)) })

    expect(signedInAs()).toBe('nobody')
  })

  /** An older login must never overwrite the session established by a newer one. */
  it('a superseded login attempt never replaces a newer session', async () => {
    const user = userEvent.setup()
    const slowFirst = deferred<Response>()
    vi.mocked(fetch)
      .mockReturnValueOnce(slowFirst.promise as unknown as Promise<Response>)   // stalls
      .mockResolvedValueOnce(jsonResponse(BEACON_ADMIN))                        // second login

    renderHarness()
    await user.click(screen.getByRole('button', { name: /attempt acme/i }))
    await user.click(screen.getByRole('button', { name: /attempt beacon/i }))
    await waitFor(() => expect(signedInAs()).toBe('Beacon Admin'))

    // The stalled first login now lands. It is superseded, so it must change nothing.
    await act(async () => { slowFirst.resolve(jsonResponse(ACME_ADMIN)) })

    expect(signedInAs()).toBe('Beacon Admin')
  })

  /**
   * Reproduced defect: sign-in released ownership before awaiting cache cancellation, so a
   * sign-out during that await left the completing login free to restore the session it had just
   * ended — the signed-out tenant's data coming back on screen.
   */
  it('a sign-out during cache cancellation prevents the login restoring its session', async () => {
    const user = userEvent.setup()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const gate = gateCancellation(client)
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(ACME_ADMIN))

    renderHarness(client)
    await user.click(screen.getByRole('button', { name: /attempt acme/i }))
    await waitFor(() => expect(client.cancelQueries).toHaveBeenCalled())   // parked mid-cancellation

    await user.click(screen.getByRole('button', { name: /harness sign out/i }))
    await act(async () => { gate.resolve() })

    expect(signedInAs()).toBe('nobody')
  })

  /** The same window, but a newer tenant wins it: the older login must not overwrite them. */
  it('a newer login during cancellation is not overwritten by the older attempt', async () => {
    const user = userEvent.setup()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const gate = gateCancellation(client)                                   // holds the first call
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(BEACON_ADMIN))

    renderHarness(client)
    await user.click(screen.getByRole('button', { name: /attempt acme/i }))
    await waitFor(() => expect(client.cancelQueries).toHaveBeenCalledTimes(1))

    await user.click(screen.getByRole('button', { name: /attempt beacon/i }))
    await waitFor(() => expect(signedInAs()).toBe('Beacon Admin'))

    await act(async () => { gate.resolve() })                               // acme finally resumes

    expect(signedInAs()).toBe('Beacon Admin')
  })

  /** A stale rejection landing while a newer attempt is mid-cancellation must change nothing. */
  it('a stale login failure does not disturb a newer attempt', async () => {
    const user = userEvent.setup()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const stale = deferred<Response>()
    const gate = gateCancellation(client)
    vi.mocked(fetch)
      .mockReturnValueOnce(stale.promise as unknown as Promise<Response>)   // acme: stalls
      .mockResolvedValueOnce(jsonResponse(BEACON_ADMIN))

    renderHarness(client)
    await user.click(screen.getByRole('button', { name: /attempt acme/i }))
    await user.click(screen.getByRole('button', { name: /attempt beacon/i }))
    await waitFor(() => expect(client.cancelQueries).toHaveBeenCalledTimes(1))

    // Beacon is parked in cancellation; acme's request now fails.
    await act(async () => { stale.resolve(jsonResponse({ type: 'x', status: 500, detail: 'no' }, 500)) })
    await act(async () => { gate.resolve() })

    expect(signedInAs()).toBe('Beacon Admin')
  })

  /** A late 401 belonging to an ended session must not log out the session that replaced it. */
  it('a late 401 from an old session does not end the newer session', async () => {
    const user = userEvent.setup()
    const staleContext = deferred<Response>()
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockReturnValueOnce(staleContext.promise as unknown as Promise<Response>)  // never resolves yet
      .mockResolvedValueOnce(jsonResponse(BEACON_ADMIN))
      .mockResolvedValueOnce(jsonResponse(beaconContext))

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await user.click(await screen.findByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)
    await signIn(user, 'beacon.admin', 'secret-b')
    expect(await screen.findByText('Beacon Labs')).toBeVisible()

    await act(async () => { staleContext.resolve(unauthorised) })

    expect(screen.getByText('Beacon Labs')).toBeVisible()
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument()
  })

  /**
   * What this actually proves: an authenticated request answered with 401 clears the current
   * session and returns the user to sign-in. The token's own lifetime is enforced server-side —
   * nothing in the client watches a clock, and no refresh or proactive expiry exists.
   */
  it('clears the current session when an authenticated request returns 401', async () => {
    const user = userEvent.setup()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })

    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(ACME_ADMIN))
      .mockResolvedValueOnce(jsonResponse(acmeContext))
      .mockResolvedValue(unauthorised)                      // the token is no longer accepted

    render(<App client={client} />)
    await signIn(user, 'acme.admin', 'secret-a')
    expect(await screen.findByText('Acme Engineering')).toBeVisible()

    await act(async () => { await client.invalidateQueries() })

    await waitFor(() => expect(screen.getByLabelText(/username/i)).toBeVisible())
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
  })

  it('handles a non-JSON error body without leaking it', async () => {
    const user = userEvent.setup()
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: false, status: 500,
      json: async () => { throw new SyntaxError('not json') },
    } as unknown as Response)

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')

    const alert = await screen.findByRole('alert')
    expect(alert).toBeVisible()
    expect(alert.textContent).not.toContain('stack trace')
    expect(alert).toHaveTextContent(/something went wrong/i)
  })
})
