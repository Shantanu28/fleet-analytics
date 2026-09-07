import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { SessionProvider, useSession } from '../auth/session'
import { dashboard as dashboardFixture } from './dashboardFixtures'

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

const CONTEXT_BY_TOKEN: Record<string, typeof acmeContext> = {
  'tok-acme': acmeContext,
  'tok-beacon': beaconContext,
}

/** Each tenant's own seat count, so an isolation assertion still discriminates on a number. */
const LICENSED_SEATS_BY_TOKEN: Record<string, number> = { 'tok-acme': 2, 'tok-beacon': 1 }

/**
 * A dashboard response consistent with that tenant's coverage: the last complete day is
 * `dataThrough − 1`, and the default range is the 30 days ending there.
 */
function dashboardFor(token: string, licensedSeats?: number) {
  const context = CONTEXT_BY_TOKEN[token] ?? acmeContext
  const through = Date.parse(context.coverage.dataThrough)
  const day = 24 * 60 * 60 * 1000
  const iso = (at: number) => new Date(at).toISOString().slice(0, 10)
  const to = iso(through - day)
  const from = iso(through - 30 * day)
  return {
    ...dashboardFixture,
    coverage: context.coverage,
    selection: {
      ...dashboardFixture.selection,
      from,
      to,
      startInclusive: `${from}T00:00:00Z`,
      endExclusive: context.coverage.dataThrough,
      previousFrom: iso(through - 60 * day),
      previousTo: iso(through - 31 * day),
      observationCutoff: to,
    },
    kpis: {
      ...dashboardFixture.kpis,
      seats: {
        ...dashboardFixture.kpis.seats,
        licensedSeats: licensedSeats ?? LICENSED_SEATS_BY_TOKEN[token] ?? 2,
      },
    },
    trends: {
      mergedPrsPerDay: { state: 'ok' as const, unit: 'count' as const, points: [{ date: to, value: 1 }] },
      spendPerDay: { state: 'ok' as const, unit: 'usdCents' as const, points: [{ date: to, spendCents: 150 }] },
    },
  }
}

type Handlers = {
  login?: (call: number) => Response | Promise<Response>
  logout?: (token: string, init?: RequestInit) => Response | Promise<Response>
  context?: (token: string, call: number) => Response | Promise<Response>
  dashboard?: (token: string, call: number) => Response | Promise<Response>
}

/**
 * Routes by URL rather than by call order.
 *
 * The authenticated page issues two requests, and the dashboard one is re-issued once coverage
 * resolves the default dates. An ordered `mockResolvedValueOnce` chain would hand the wrong body to
 * the wrong endpoint the moment that count changed, so tests state what each endpoint answers and
 * stay indifferent to how many times it is asked.
 */
function routeApi(handlers: Handlers = {}) {
  const calls = { login: 0, context: 0, dashboard: 0 }
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const authorization = (init?.headers as Record<string, string> | undefined)?.Authorization ?? ''
    const token = authorization.replace(/^Bearer /, '')

    if (url.includes('/auth/login')) {
      calls.login += 1
      return Promise.resolve(handlers.login?.(calls.login) ?? jsonResponse(ACME_ADMIN))
    }
    if (url.includes('/auth/logout')) {
      return Promise.resolve(handlers.logout?.(token, init) ?? new Response(null, { status: 204 }))
    }
    if (url.includes('/analytics/context')) {
      calls.context += 1
      const routed = handlers.context?.(token, calls.context)
      if (routed !== undefined) return Promise.resolve(routed)
      const body = CONTEXT_BY_TOKEN[token]
      return Promise.resolve(body ? jsonResponse(body) : unauthorised)
    }
    if (url.includes('/analytics/dashboard')) {
      calls.dashboard += 1
      const routed = handlers.dashboard?.(token, calls.dashboard)
      if (routed !== undefined) return Promise.resolve(routed)
      return Promise.resolve(
        CONTEXT_BY_TOKEN[token] ? jsonResponse(dashboardFor(token)) : unauthorised,
      )
    }
    return Promise.resolve(unauthorised)
  })
  return calls
}

/** The team and repository selects are where a tenant's own scopes are now offered. */
function filterOption(name: RegExp, option: string) {
  return within(screen.getByRole('combobox', { name })).getByRole('option', { name: option })
}

/**
 * The seats card, found by its visible heading.
 *
 * Scoped rather than matched on a bare number: figures like "2" appear in several cards, and an
 * unscoped query would pass or fail for reasons unrelated to seats.
 */
function seatsCard(): HTMLElement {
  const card = screen.getByRole('heading', { name: /active seats/i }).closest('article')
  if (card === null) throw new Error('seats card not found')
  return card
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  // The applied selection lives in the URL, so each test starts from a clean history entry.
  window.history.replaceState(null, '', '/')
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers() })

async function signIn(user: ReturnType<typeof userEvent.setup>, username: string, password: string) {
  await user.clear(screen.getByLabelText(/username/i))
  await user.type(screen.getByLabelText(/username/i), username)
  await user.clear(screen.getByLabelText(/password/i))
  await user.type(screen.getByLabelText(/password/i), password)
  await user.click(screen.getByRole('button', { name: /sign in/i }))
}

describe('session', () => {
  it('revokes the current bearer while immediately clearing the page and cache', async () => {
    const user = userEvent.setup()
    const pending = deferred<Response>()
    const logout = vi.fn((_token: string, _init?: RequestInit) => pending.promise)
    routeApi({ logout })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<App client={client} />)
    await signIn(user, 'acme.admin', 'secret-a')
    expect(await screen.findByText('Acme Engineering')).toBeVisible()
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    expect(screen.getByLabelText(/username/i)).toBeVisible()
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
    expect(client.getQueryCache().getAll()).toHaveLength(0)
    expect(logout).toHaveBeenCalledWith('tok-acme', expect.objectContaining({ method: 'POST' }))
    await act(async () => { pending.resolve(new Response(null, { status: 204 })) })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each([500, 503])('warns when server logout fails with %i without restoring local data', async (status) => {
    const user = userEvent.setup()
    routeApi({ logout: () => jsonResponse({ detail: 'restricted-server-details' }, status) })
    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/server sign-out could not be confirmed/i)
    expect(alert).not.toHaveTextContent('restricted-server-details')
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
  })

  it('an old logout failure cannot disturb a new login', async () => {
    const user = userEvent.setup()
    const pending = deferred<Response>()
    routeApi({
      login: (call) => jsonResponse(call === 1 ? ACME_ADMIN : BEACON_ADMIN),
      logout: () => pending.promise,
    })
    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    await signIn(user, 'beacon.admin', 'secret-b')
    await screen.findByText('Beacon Labs')
    await act(async () => { pending.resolve(jsonResponse({}, 500)) })
    expect(screen.getByText('Beacon Labs')).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('warns on a network failure during logout while keeping the browser signed out', async () => {
    const user = userEvent.setup()
    routeApi({ logout: () => Promise.reject(new TypeError('Network failure')) })
    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/server sign-out could not be confirmed/i)
    expect(screen.getByLabelText(/username/i)).toBeVisible()
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
  })

  it('signs in and shows the authenticated organisation', async () => {
    const user = userEvent.setup()
    routeApi()

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')

    expect(await screen.findByText('Acme Engineering')).toBeVisible()
    // The organisation's own teams and repositories are now the filter options.
    expect(filterOption(/team/i, 'Acme Platform')).toBeInTheDocument()
    expect(filterOption(/repository/i, 'acme-api')).toBeInTheDocument()
    expect(seatsCard()).toHaveTextContent('2 / 2')              // active of licensed
  })

  /** AC-01.3: the cutoff is dataThrough − 1 day, never dataThrough itself. */
  it('states the reporting cutoff as the last complete UTC day', async () => {
    const user = userEvent.setup()
    routeApi()

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')

    expect(await screen.findByText(/complete through 28 Feb 2026 \(UTC\)/i)).toBeVisible()
    expect(screen.queryByText(/2026-03-01/)).not.toBeInTheDocument()
    expect(screen.queryByText(/1 Mar 2026/)).not.toBeInTheDocument()
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
    routeApi({ context: () => unauthorised, dashboard: () => unauthorised })

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByLabelText(/username/i)                   // the 401 returned us to login

    // Whatever the authenticated page asked for, it asked once: no attempt was retried.
    const callsSoFar = vi.mocked(fetch).mock.calls.length

    // A retry would be scheduled on a timer. Switching to a controlled clock only now — after the
    // DOM has settled — lets us run well past any backoff without polling a stubbed timer.
    vi.useFakeTimers()
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    vi.useRealTimers()

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(callsSoFar)
  })

  it('logout clears protected data and explains server-side revocation', async () => {
    const user = userEvent.setup()
    routeApi()

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    expect(screen.getByText(/revokes its token on the server/i)).toBeVisible()

    await user.click(screen.getByRole('button', { name: /sign out/i }))

    expect(await screen.findByLabelText(/username/i)).toBeVisible()
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
  })

  it('a context response arriving after logout never repopulates the view', async () => {
    const user = userEvent.setup()
    const late = deferred<Response>()
    routeApi({ context: () => late.promise })

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
    routeApi({ login: (call) => jsonResponse(call === 1 ? ACME_ADMIN : BEACON_ADMIN) })

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await screen.findByText('Acme Engineering')
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)
    await signIn(user, 'beacon.admin', 'secret-b')

    expect(await screen.findByText('Beacon Labs')).toBeVisible()
    expect(filterOption(/team/i, 'Beacon Core')).toBeInTheDocument()
    expect(filterOption(/repository/i, 'beacon-web')).toBeInTheDocument()
    expect(seatsCard()).toHaveTextContent('2 / 1')              // Beacon's own licensed seats
    expect(screen.queryByText('Acme Engineering')).not.toBeInTheDocument()
    expect(screen.queryByText('Acme Platform')).not.toBeInTheDocument()
    expect(screen.queryByText('acme-api')).not.toBeInTheDocument()
  })

  /** The same person signing back in must not see the previous session's cached answer. */
  it('a second session for the same user and role refetches rather than reusing the cache', async () => {
    const user = userEvent.setup()
    // The same person, the same role, but the dataset has changed underneath them. A reused cache
    // entry would still show 2.
    const calls = routeApi({
      dashboard: (token, call) => jsonResponse(dashboardFor(token, call <= 2 ? 2 : 7)),
    })

    render(<App />)
    await signIn(user, 'acme.admin', 'secret-a')
    await waitFor(() => expect(seatsCard()).toHaveTextContent('2 / 2'))

    await user.click(screen.getByRole('button', { name: /sign out/i }))
    await screen.findByLabelText(/username/i)
    await signIn(user, 'acme.admin', 'secret-a')

    await waitFor(() => expect(seatsCard()).toHaveTextContent('2 / 7'))
    expect(seatsCard()).not.toHaveTextContent('2 / 2')
    // Both endpoints were asked again rather than answered from the previous session's cache.
    expect(calls.context).toBe(2)
    expect(calls.dashboard).toBeGreaterThan(2)
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
    routeApi({
      login: (call) => jsonResponse(call === 1 ? ACME_ADMIN : BEACON_ADMIN),
      // Acme's context never resolves until the test releases it, long after that session ended.
      context: (token) => (token === 'tok-acme' ? staleContext.promise : jsonResponse(beaconContext)),
      dashboard: (token) =>
        token === 'tok-acme' ? new Promise<Response>(() => {}) : jsonResponse(dashboardFor(token)),
    })

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

    let accepted = true
    routeApi({
      context: (token) => (accepted && token === 'tok-acme' ? jsonResponse(acmeContext) : unauthorised),
      dashboard: (token) =>
        accepted && token === 'tok-acme' ? jsonResponse(dashboardFor(token)) : unauthorised,
    })

    render(<App client={client} />)
    await signIn(user, 'acme.admin', 'secret-a')
    expect(await screen.findByText('Acme Engineering')).toBeVisible()

    accepted = false                                        // the token is no longer accepted
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
