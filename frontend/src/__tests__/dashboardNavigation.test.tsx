import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import type { DashboardResponse } from '../api/dashboard'
import {
  REPO_API,
  TEAM_PAYMENTS,
  budgetFinding,
  context,
  dashboard,
  failureFinding,
  frictionFinding,
  withAttention,
  withSelection,
} from './dashboardFixtures'

const LOGIN = {
  accessToken: 'tok-n',
  expiresInSeconds: 900,
  userId: 'u-n',
  organisationId: 'org-n',
  displayName: 'Nora Admin',
  role: 'ADMIN' as const,
}

const VIEWER_LOGIN = { ...LOGIN, displayName: 'Vic Viewer', role: 'VIEWER' as const }

function jsonResponse(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

/** Routed on the requested query string, which is how a destination differs from its origin. */
function routeApi(options: {
  login?: unknown
  role?: 'ADMIN' | 'VIEWER'
  dashboard: (search: string) => DashboardResponse
}) {
  const requested: string[] = []
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/auth/login')) {
      return Promise.resolve(jsonResponse(options.login ?? LOGIN))
    }
    if (url.includes('/analytics/context')) {
      return Promise.resolve(
        jsonResponse({ ...context, role: options.role ?? 'ADMIN' }),
      )
    }
    const search = url.split('?')[1] ?? ''
    requested.push(search)
    return Promise.resolve(jsonResponse(options.dashboard(search)))
  })
  return requested
}

function freshClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, retryDelay: 0, refetchOnMount: false, staleTime: Infinity },
    },
  })
}

async function signIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/username/i), 'admin')
  await user.type(screen.getByLabelText(/password/i), 'pw')
  await user.click(screen.getByRole('button', { name: /sign in/i }))
  await screen.findByText('Northstar Engineering')
}

/** The investigation origin from the M4 scenario: 2–31 Aug 2026, Payments, no repository filter. */
const ORIGIN = `/?from=2026-08-02&to=2026-08-31&teamId=${TEAM_PAYMENTS}&grouping=teams`

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  window.history.replaceState(null, '', '/')
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the attention panel states', () => {
  const cases: ReadonlyArray<
    readonly [string, DashboardResponse['attention'], RegExp | null, boolean]
  > = [
    // findings, completed, unavailable -> what the panel must say
    ['a: findings, no limits', { findings: [budgetFinding], evaluationsCompleted: 3, limits: [] }, null, false],
    [
      'b: findings and limits',
      {
        findings: [budgetFinding],
        evaluationsCompleted: 3,
        limits: [
          {
            ruleType: 'merge_rate_decline',
            scopeType: 'team',
            scopeId: TEAM_PAYMENTS,
            scopeName: 'Payments',
            state: 'not_evaluated',
            reasonCode: 'gate_terminal_prs_15',
            reason: 'Needs 15 terminal PRs in each period; this scope had 9.',
          },
        ],
      },
      null,
      true,
    ],
    ['c: nothing triggered', { findings: [], evaluationsCompleted: 4, limits: [] }, /no configured alerts triggered/i, false],
    [
      'd: nothing triggered, some could not run',
      {
        findings: [],
        evaluationsCompleted: 2,
        limits: [
          {
            ruleType: 'budget_risk',
            scopeType: 'team',
            scopeId: TEAM_PAYMENTS,
            scopeName: 'Payments',
            state: 'not_evaluated',
            reasonCode: 'no_budget_configured',
            reason: 'No budget is configured for this team.',
          },
        ],
      },
      /no alerts triggered among the rules that could be evaluated/i,
      true,
    ],
    [
      'e: nothing could be evaluated',
      {
        findings: [],
        evaluationsCompleted: 0,
        limits: [
          {
            ruleType: 'budget_risk',
            scopeType: 'organisation',
            scopeId: null,
            state: 'unavailable_for_scope',
            reasonCode: 'budget_unavailable_for_repository_scope',
            reason: 'Budgets have no repository allocation.',
          },
        ],
      },
      /could not be evaluated/i,
      true,
    ],
  ]

  it.each(cases)('renders case %s', async (_name, attention, message, expectLimits) => {
    const user = userEvent.setup()
    routeApi({ dashboard: () => withAttention(dashboard, attention) })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })

    if (message === null) {
      expect(within(panel).getAllByRole('article').length).toBe(attention.findings.length)
    } else {
      expect(panel).toHaveTextContent(message)
      expect(within(panel).queryAllByRole('article')).toHaveLength(0)
    }

    // A limit notice is compact and explanatory, and is never one of the findings.
    if (expectLimits) {
      expect(within(panel).getByText(/checks? could not run/i)).toBeVisible()
      expect(panel).toHaveTextContent(/does not mean everything is fine/i)
    } else {
      expect(within(panel).queryByText(/checks? could not run/i)).not.toBeInTheDocument()
    }
  })

  /** Case (e) must never read as health, even though nothing triggered. */
  it('never claims health when nothing could be evaluated', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, { findings: [], evaluationsCompleted: 0, limits: [] }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).not.toHaveTextContent(/no configured alerts triggered/i)
    expect(panel).toHaveTextContent(/nothing below is a statement that everything is fine/i)
  })

  /** AC-07.8: budget has its own reporting month, so page-level emptiness must not hide it. */
  it('keeps a budget finding visible when the selected period is empty', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () => ({
        ...withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
        comparison: { ...dashboard.comparison, rows: [] },
      }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/payments is on course to overspend/i)
    expect(panel).toHaveTextContent(/independent of the dates selected above/i)
  })
})

describe('budget finding copy', () => {
  /**
   * The severity is only legible next to the thresholds it was judged against. Both are strict
   * (contract §6.1), and stating them is explanatory copy — the server still decides whether a
   * finding fires and at what severity.
   */
  it('states the approved thresholds and identifies the period as UTC', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    await user.click(within(panel).getByText(/when this is flagged/i))

    expect(panel).toHaveTextContent(/more than 10% over budget/i)
    expect(panel).toHaveTextContent(/HIGH when it runs more than 20% over/i)
    expect(panel).toHaveTextContent(/exactly 10% does not flag/i)
    expect(panel).toHaveTextContent(/exactly 20% stays MEDIUM/i)
    // The server's own figures remain the authority on screen.
    expect(panel).toHaveTextContent(/overspend its budget by 18.0%/i)
    expect(panel).toHaveTextContent('$11,800')
    expect(within(panel).getByText('MEDIUM')).toBeVisible()
    // Evaluation dates say which calendar they are in.
    expect(panel).toHaveTextContent(/1–14 Aug 2026 \(UTC\)/)
  })

  /** Only budget findings carry the budget thresholds. */
  it('does not attach budget threshold copy to other rules', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [failureFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).not.toHaveTextContent(/over budget/i)
    expect(panel).toHaveTextContent(/2–31 Aug 2026 \(UTC\)/)
  })
})

describe('role-based evidence', () => {
  const withFriction = (domain?: string) =>
    withAttention(dashboard, {
      findings: [frictionFinding(domain)],
      evaluationsCompleted: 2,
      limits: [],
    })

  it('shows the denied domain to an ADMIN', async () => {
    const user = userEvent.setup()
    routeApi({ dashboard: () => withFriction('internal-registry.corp') })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent('internal-registry.corp')
    expect(panel).toHaveTextContent(/for 6 tasks across 4 people/i)
  })

  /** Same counts, no domain anywhere in the rendered page or its links (AC-06.9). */
  it('shows a VIEWER the same counts and no domain', async () => {
    const user = userEvent.setup()
    routeApi({
      login: VIEWER_LOGIN,
      role: 'VIEWER',
      dashboard: () => withFriction(),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/for 6 tasks across 4 people/i)
    expect(panel).toHaveTextContent(/visible to platform admins only/i)
    expect(document.body.textContent).not.toContain('internal-registry')
    expect(window.location.search).not.toContain('registry')
  })

  /**
   * A `?? 0` fallback here would have claimed the domain affected nobody — the opposite of what an
   * absent count means, and self-refuting, since the rule cannot fire without both counts clearing
   * their thresholds.
   */
  it('never renders an absent friction count as zero', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [
            {
              ...frictionFinding('internal-registry.corp'),
              evidence: { domain: 'internal-registry.corp', thresholdTasks: 5, thresholdUsers: 3 },
            },
          ],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/affected counts are not available for this period/i)
    expect(panel).not.toHaveTextContent(/0 tasks/)
    expect(panel).not.toHaveTextContent(/0 people/)
    // The domain and the thresholds are still shown, because those were supplied.
    expect(panel).toHaveTextContent('internal-registry.corp')
    expect(panel).toHaveTextContent(/5 or more tasks/i)
  })

  it('renders only the half of a partially supplied friction count that exists', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [
            {
              ...frictionFinding('internal-registry.corp'),
              evidence: { domain: 'internal-registry.corp', distinctTasks: 6 },
            },
          ],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/for 6 tasks in Payments/i)
    expect(panel).not.toHaveTextContent(/people/i)
  })

  /** Redaction is unaffected by an absent count: still no domain for a VIEWER. */
  it('keeps a VIEWER free of the domain when counts are absent', async () => {
    const user = userEvent.setup()
    routeApi({
      login: VIEWER_LOGIN,
      role: 'VIEWER',
      dashboard: () =>
        withAttention(dashboard, {
          findings: [{ ...frictionFinding(), evidence: { thresholdTasks: 5, thresholdUsers: 3 } }],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/visible to platform admins only/i)
    expect(document.body.textContent).not.toContain('internal-registry')
    expect(panel).not.toHaveTextContent(/0 tasks/)
  })

  /** Optional evidence can legitimately be absent after coverage gating; absence is not zero. */
  it('renders a finding whose optional evidence is absent without inventing counts', async () => {
    const user = userEvent.setup()
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [
            {
              ...failureFinding,
              evidence: { currentRate: { value: '34.0', unit: 'percent' } },
            },
          ],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/repo-api failures rose 12.0 pp/i)
    expect(panel).toHaveTextContent('34.0%')
    expect(panel).not.toHaveTextContent(/0 tasks failed/i)
    expect(panel).not.toHaveTextContent(/from the agent/i)
  })
})

describe('following a budget finding', () => {
  it('sets the finding’s own month, keeps its team and clears the repository filter', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', `${ORIGIN}&repositoryId=${REPO_API}`)
    const requested = routeApi({
      dashboard: (search) =>
        withAttention(
          withSelection(dashboard, {
            from: search.includes('from=2026-08-01') ? '2026-08-01' : '2026-08-02',
            to: search.includes('to=2026-08-14') ? '2026-08-14' : '2026-08-31',
          }),
          { findings: [budgetFinding], evaluationsCompleted: 1, limits: [] },
        ),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    await user.click(await screen.findByRole('button', { name: /inspect payments spend/i }))

    await waitFor(() => expect(window.location.search).toContain('from=2026-08-01'))
    expect(window.location.search).toContain('to=2026-08-14')
    expect(window.location.search).toContain(`teamId=${TEAM_PAYMENTS}`)
    // Cleared, not preserved: budgets have no repository allocation.
    expect(window.location.search).not.toContain('repositoryId')

    const destination = requested[requested.length - 1] ?? ''
    expect(destination).toContain('from=2026-08-01')
    expect(destination).not.toContain('repositoryId')

    // The reporting-period change is explained on screen.
    expect(await screen.findByText(/finding’s own reporting period/i)).toBeVisible()
  })

  it('returns to the originating selection on Back', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    await user.click(await screen.findByRole('button', { name: /inspect payments spend/i }))
    await waitFor(() => expect(window.location.search).toContain('from=2026-08-01'))

    window.history.back()

    await waitFor(() => expect(window.location.search).toContain('from=2026-08-02'))
    expect(window.location.search).toContain('to=2026-08-31')
    expect(window.location.search).toContain(`teamId=${TEAM_PAYMENTS}`)
    expect(window.location.search).toContain('grouping=teams')
  })
})

describe('following a failure-spike finding', () => {
  /** AC-06.12: Payments stays, the repository is applied, the table switches and the row focuses. */
  it('keeps the selected team, applies the repository, and switches grouping', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    const requested = routeApi({
      dashboard: (search) =>
        withAttention(
          {
            ...withSelection(dashboard, {
              teamId: TEAM_PAYMENTS,
              repositoryId: search.includes('repositoryId') ? REPO_API : null,
              grouping: search.includes('grouping=repositories') ? 'repositories' : 'teams',
            }),
            comparison: {
              ...dashboard.comparison,
              grouping: search.includes('grouping=repositories') ? 'repositories' : 'teams',
              rows: [
                {
                  ...dashboard.comparison.rows[0]!,
                  scopeId: REPO_API,
                  scopeName: 'repo-api',
                },
              ],
            },
          },
          { findings: [failureFinding], evaluationsCompleted: 1, limits: [] },
        ),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    await user.click(await screen.findByRole('button', { name: /inspect repo-api/i }))

    await waitFor(() => expect(window.location.search).toContain(`repositoryId=${REPO_API}`))
    // The other dimension and the dates were omitted from the link, so they are preserved.
    expect(window.location.search).toContain(`teamId=${TEAM_PAYMENTS}`)
    expect(window.location.search).toContain('from=2026-08-02')
    expect(window.location.search).toContain('to=2026-08-31')
    expect(window.location.search).toContain('grouping=repositories')
    expect(requested[requested.length - 1]).toContain('grouping=repositories')

    // The affected row is brought into view once its own response has rendered.
    await waitFor(() =>
      expect(document.querySelector(`[data-scope-id="${REPO_API}"]`)).toHaveAttribute(
        'aria-current',
        'true',
      ),
    )
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })
})

describe('following a network-friction finding', () => {
  const frictionOnly = (findings: DashboardResponse['attention']['findings']) =>
    withAttention(dashboard, { findings, evaluationsCompleted: 2, limits: [] })

  /** The destination recomputes. If the finding survives, its *new* evidence is what is shown. */
  it('shows the recomputed evidence when the finding survives', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', '/?from=2026-08-02&to=2026-08-31&grouping=teams')
    routeApi({
      dashboard: (search) =>
        frictionOnly([
          search.includes(`teamId=${TEAM_PAYMENTS}`)
            ? {
                ...frictionFinding('internal-registry.corp'),
                evidence: {
                  domain: 'internal-registry.corp',
                  distinctTasks: 5,
                  distinctUsers: 3,
                  thresholdTasks: 5,
                  thresholdUsers: 3,
                },
              }
            : frictionFinding('internal-registry.corp'),
        ]),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    expect(panel).toHaveTextContent(/for 6 tasks across 4 people/i)

    await user.click(within(panel).getByRole('button', { name: /inspect payments/i }))

    // Narrower scope, recomputed counts: the old evidence must not persist.
    await waitFor(() =>
      expect(screen.getByRole('region', { name: /needs attention/i })).toHaveTextContent(
        /for 5 tasks across 3 people/i,
      ),
    )
    expect(screen.getByRole('region', { name: /needs attention/i })).not.toHaveTextContent(
      /for 6 tasks across 4 people/i,
    )
    expect(screen.getByText('Findings recalculated for the selected filters.')).toBeVisible()
    expect(document.querySelector('.finding[aria-current]')).toBeNull()
  })

  /** The notice describes recalculation without claiming to track the previous finding. */
  it('shows the recalculated notice when the destination has no findings', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', '/?from=2026-08-02&to=2026-08-31&grouping=teams')
    routeApi({
      dashboard: (search) =>
        frictionOnly(
          search.includes(`teamId=${TEAM_PAYMENTS}`)
            ? []
            : [frictionFinding('internal-registry.corp')],
        ),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    await user.click(within(panel).getByRole('button', { name: /inspect payments/i }))

    expect(await screen.findByText('Findings recalculated for the selected filters.')).toBeVisible()
    const after = screen.getByRole('region', { name: /needs attention/i })
    expect(after).not.toHaveTextContent(/for 6 tasks across 4 people/i)
    expect(within(after).queryAllByRole('article')).toHaveLength(0)
  })

  /**
   * A destination already in the cache must still be re-evaluated: serving the previous answer
   * would present stale evidence as the newly recomputed result.
   */
  it('obtains fresh evidence even when the destination was already cached', async () => {
    const user = userEvent.setup()
    const client = freshClient()
    window.history.replaceState(null, '', '/?from=2026-08-02&to=2026-08-31&grouping=teams')

    let paymentsTasks = 9
    const requested = routeApi({
      dashboard: (search) =>
        frictionOnly([
          search.includes(`teamId=${TEAM_PAYMENTS}`)
            ? {
                ...frictionFinding('internal-registry.corp'),
                evidence: {
                  domain: 'internal-registry.corp',
                  distinctTasks: paymentsTasks,
                  distinctUsers: 3,
                  thresholdTasks: 5,
                  thresholdUsers: 3,
                },
              }
            : frictionFinding('internal-registry.corp'),
        ]),
    })
    render(<App client={client} />)
    await signIn(user)

    // Visit Payments once, so its response is cached, then come back to the origin.
    await user.click(screen.getByRole('combobox', { name: /team/i }))
    await user.selectOptions(screen.getByRole('combobox', { name: /team/i }), TEAM_PAYMENTS)
    await waitFor(() => expect(window.location.search).toContain(`teamId=${TEAM_PAYMENTS}`))
    await waitFor(() =>
      expect(screen.getByRole('region', { name: /needs attention/i })).toHaveTextContent(
        /for 9 tasks across 3 people/i,
      ),
    )

    await user.selectOptions(screen.getByRole('combobox', { name: /team/i }), '')
    await waitFor(() => expect(window.location.search).not.toContain('teamId'))

    // The dataset moves on, then the finding link is followed into the cached destination.
    paymentsTasks = 11
    const before = requested.length
    await user.click(
      within(screen.getByRole('region', { name: /needs attention/i })).getByRole('button', {
        name: /inspect payments/i,
      }),
    )

    await waitFor(() =>
      expect(screen.getByRole('region', { name: /needs attention/i })).toHaveTextContent(
        /for 11 tasks across 3 people/i,
      ),
    )
    expect(requested.length).toBeGreaterThan(before)
  })
})

describe('destination navigation', () => {
  /** AC-06.4: a budget link's destination is the spend trend, so the page must land there. */
  it('brings the spend trend into view for a budget finding', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    // Spied on the prototype, not on the current node: the sections unmount while the destination
    // request is pending, so the element that eventually reveals itself is a new one.
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')

    await user.click(await screen.findByRole('button', { name: /inspect payments spend/i }))
    await waitFor(() => expect(screen.getByText(/finding’s own reporting period/i)).toBeVisible())

    await waitFor(() => expect(scrollSpy).toHaveBeenCalled())
    // Focus follows, so a keyboard user arrives at the destination rather than staying at the link.
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('region', { name: /daily trends/i })),
    )
  })

  /**
   * AC-06.7: the destination is the panel, not an individual finding. Reveal it even when the
   * recomputed response contains no findings.
   */
  it('lands at attention even when the followed friction finding disappears', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', '/?from=2026-08-02&to=2026-08-31&grouping=teams')
    routeApi({
      dashboard: (search) =>
        withAttention(dashboard, {
          findings: search.includes(`teamId=${TEAM_PAYMENTS}`)
            ? []
            : [frictionFinding('internal-registry.corp')],
          evaluationsCompleted: 2,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')

    await user.click(within(panel).getByRole('button', { name: /inspect payments/i }))

    expect(await screen.findByText('Findings recalculated for the selected filters.')).toBeVisible()
    await waitFor(() => expect(scrollSpy).toHaveBeenCalled())
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('region', { name: /needs attention/i })),
    )
  })

  /** Nothing moves and nothing takes focus on an ordinary filter change. */
  it('does not scroll or steal focus on a normal filter change', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    routeApi({ dashboard: () => dashboard })
    render(<App client={freshClient()} />)
    await signIn(user)
    await screen.findByRole('region', { name: /daily trends/i })

    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
    const preset = screen.getByRole('button', { name: /^7 days$/i })
    preset.focus()

    await user.click(preset)
    await waitFor(() => expect(window.location.search).toContain('from=2026-08-25'))

    expect(scrollSpy).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(preset)
  })

  /**
   * A destination whose selection equals the current one still has to re-evaluate: the friction
   * link's whole purpose is the recomputed answer, and a cached hit would present the old one.
   */
  it('re-requests a friction destination even when the selection does not change', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', `${ORIGIN}`)
    let tasks = 6
    const requested = routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [
            {
              ...frictionFinding('internal-registry.corp'),
              evidence: {
                domain: 'internal-registry.corp',
                distinctTasks: tasks,
                distinctUsers: 4,
                thresholdTasks: 5,
                thresholdUsers: 3,
              },
            },
          ],
          evaluationsCompleted: 2,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    const panel = await screen.findByRole('region', { name: /needs attention/i })
    await waitFor(() => expect(panel).toHaveTextContent(/for 6 tasks across 4 people/i))

    // The finding's own scope already matches the applied selection, so the URL will not change.
    const before = requested.length
    tasks = 7
    await user.click(within(panel).getByRole('button', { name: /inspect payments/i }))

    await waitFor(() =>
      expect(screen.getByRole('region', { name: /needs attention/i })).toHaveTextContent(
        /for 7 tasks across 4 people/i,
      ),
    )
    expect(requested.length).toBeGreaterThan(before)
  })
})

describe('transient navigation state', () => {
  it('keeps no finding payload in the URL and drops the investigation on a later filter change', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    await user.click(await screen.findByRole('button', { name: /inspect payments spend/i }))
    await waitFor(() => expect(screen.getByText(/finding’s own reporting period/i)).toBeVisible())

    // Only documented selection parameters ever reach the URL.
    for (const forbidden of ['finding', 'focusRowId', 'section', 'tok-']) {
      expect(window.location.search).not.toContain(forbidden)
    }

    // A later filter change makes the investigation obsolete, so its notice goes away.
    await user.click(screen.getByRole('button', { name: /^7 days$/i }))

    await waitFor(() =>
      expect(screen.queryByText(/finding’s own reporting period/i)).not.toBeInTheDocument(),
    )
  })

  /**
   * Obsolete state is discarded, not merely hidden while the URL differs. Kept, it would spring
   * back on any later navigation to the same selection and re-announce a finding the user followed
   * long ago.
   */
  it('discards an investigation that navigation has left, so returning does not revive it', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', ORIGIN)
    routeApi({
      dashboard: () =>
        withAttention(dashboard, {
          findings: [budgetFinding],
          evaluationsCompleted: 1,
          limits: [],
        }),
    })
    render(<App client={freshClient()} />)
    await signIn(user)

    await user.click(await screen.findByRole('button', { name: /inspect payments spend/i }))
    const destination = await waitFor(() => {
      expect(window.location.search).toContain('from=2026-08-01')
      return window.location.search
    })
    expect(screen.getByText(/finding’s own reporting period/i)).toBeVisible()

    // Leave the destination, then come back to exactly the same selection.
    window.history.back()
    await waitFor(() => expect(window.location.search).toContain('from=2026-08-02'))
    window.history.forward()
    await waitFor(() => expect(window.location.search).toBe(destination))

    // Same URL, but the investigation is gone: the notice does not reappear.
    await waitFor(() =>
      expect(screen.queryByText(/finding’s own reporting period/i)).not.toBeInTheDocument(),
    )
  })
})
