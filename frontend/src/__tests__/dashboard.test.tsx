import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import {
  REPO_API,
  TEAM_PAYMENTS,
  context,
  dashboard,
  display,
  okComparison,
  okMetric,
  unavailableMetric,
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

function jsonResponse(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

function problem(type: string, status = 400): Response {
  return jsonResponse({ type, title: 'Bad Request', status, detail: 'internal detail' }, status)
}

type Handlers = {
  dashboard?: (url: string, call: number) => Response | Promise<Response>
  context?: () => Response | Promise<Response>
}

function routeApi(handlers: Handlers = {}) {
  const calls: string[] = []
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/auth/login')) return Promise.resolve(jsonResponse(LOGIN))
    if (url.includes('/analytics/context')) {
      return Promise.resolve(handlers.context?.() ?? jsonResponse(context))
    }
    calls.push(url)
    return Promise.resolve(
      handlers.dashboard?.(url, calls.length) ?? jsonResponse(dashboard),
    )
  })
  return calls
}

/**
 * A fresh client per test, with no retry backoff.
 *
 * The production retry policy lives in `useDashboardQuery` and is not overridden here — only its
 * exponential delay is removed, so a test that exercises exhausted retries does not have to wait
 * seconds of real backoff to see the error state.
 */
function freshClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, retryDelay: 0, refetchOnMount: false, staleTime: Infinity },
    },
  })
}

async function signInAndWait(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/username/i), 'admin')
  await user.type(screen.getByLabelText(/password/i), 'pw')
  await user.click(screen.getByRole('button', { name: /sign in/i }))
  await screen.findByText('Northstar Engineering')
}

async function renderDashboard(handlers: Handlers = {}) {
  const user = userEvent.setup()
  const calls = routeApi(handlers)
  render(<App client={freshClient()} />)
  await signInAndWait(user)
  return { user, calls }
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn())
  window.history.replaceState(null, '', '/')
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The seats card, scoped by its visible heading rather than by a bare number. */
function card(name: RegExp): HTMLElement {
  const found = screen.getByRole('heading', { name }).closest('article')
  if (found === null) throw new Error(`card not found: ${name}`)
  return found
}

describe('the page shell', () => {
  it('shows the organisation, role, demo framing and reporting cutoff distinctly', async () => {
    await renderDashboard()

    const banner = within(screen.getByRole('banner'))
    expect(banner.getByText('Northstar Engineering')).toBeVisible()
    expect(banner.getByText('Synthetic demo data')).toBeVisible()
    expect(banner.getByText(/viewing as nora admin/i)).toBeVisible()
    expect(banner.getByText(/\(ADMIN\)/)).toBeVisible()
    expect(banner.getByText(/complete through 31 Aug 2026 \(UTC\)/i)).toBeVisible()
    // The selected range and the comparison period are stated separately, in the header.
    expect(banner.getByText(/showing/i)).toHaveTextContent('2–31 Aug 2026')
    expect(banner.getByText(/showing/i)).toHaveTextContent('3 Jul – 1 Aug 2026')
  })

  it('keeps sign-out available while the dashboard request is still pending', async () => {
    const user = userEvent.setup()
    routeApi({ dashboard: () => new Promise<Response>(() => {}) })
    render(<App client={freshClient()} />)
    await signInAndWait(user)

    expect(screen.getByRole('button', { name: /sign out/i })).toBeEnabled()
    expect(screen.getByRole('status')).toHaveTextContent(/loading results/i)
  })

  it('explains that sign-out revokes the current token', async () => {
    await renderDashboard()

    expect(screen.getByText(/revokes its token on the server/i)).toBeVisible()
  })
})

describe('the five KPI cards', () => {
  it('renders exactly five cards in the frozen order', async () => {
    await renderDashboard()

    const headings = within(
      screen.getByRole('region', { name: /headline measures/i }),
    ).getAllByRole('heading')

    expect(headings.map((heading) => heading.textContent)).toEqual([
      'Merged agent PRs',
      'Terminal PR merge rate',
      'Blended cost per merged PR',
      'Task completion rate',
      'Active seats / licensed seats',
    ])
  })

  it('shows one comparison per card, of the type that card is assigned', async () => {
    await renderDashboard()

    // Relative percentage for merged PRs; absolute count for seats.
    expect(card(/merged agent prs/i)).toHaveTextContent('-50.0%')
    expect(card(/active seats/i)).toHaveTextContent('-1')
    // Percentage points and absolute USD are suppressed here, and say so instead.
    expect(card(/terminal pr merge rate/i)).toHaveTextContent(/needs 15 terminal PRs/i)
    expect(card(/blended cost per merged pr/i)).toHaveTextContent(/needs 15 merged PRs/i)
    expect(card(/task completion rate/i)).toHaveTextContent(/needs 20 completed or failed/i)
  })

  /**
   * A delta is meaningless without its population. A card compares against the previous period; a
   * table row compares against the organisation benchmark (contract §5.4). The comparison *kind*
   * names the units and says nothing about which — so labelling both the same way would tell a
   * reader the row was a period-over-period change it never was.
   */
  it('labels card deltas against the previous period', async () => {
    await renderDashboard()

    expect(card(/merged agent prs/i)).toHaveTextContent('vs previous period')
    expect(card(/active seats/i)).toHaveTextContent('vs previous period')
    expect(card(/merged agent prs/i)).not.toHaveTextContent(/benchmark/i)
  })

  /** A suppressed comparison must not blank the value it sits beside (AC-01.4). */
  it('keeps the value visible when its comparison is suppressed', async () => {
    await renderDashboard()

    expect(card(/terminal pr merge rate/i)).toHaveTextContent('100.0%')
    expect(card(/blended cost per merged pr/i)).toHaveTextContent('$23.00')
  })

  it('never substitutes a different comparison for an unavailable one', async () => {
    await renderDashboard()

    const mergeRate = card(/terminal pr merge rate/i)
    // Only one delta figure appears, and it is not a percentage-point value.
    expect(mergeRate).not.toHaveTextContent(/pp/)
    expect(mergeRate.querySelectorAll('.comparison').length).toBe(1)
  })

  /** Zero, unavailable and insufficient sample must read as three different things (AC-01.6). */
  it('distinguishes a real zero from an unavailable value', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          kpis: {
            ...dashboard.kpis,
            taskCompletionRate: { state: 'zero_outcome', display: display('0.0', 'percent') },
            costPerMergedPr: unavailableMetric(
              'no_denominator',
              'no_merged_prs',
              'No merged PRs in this period, so cost per merged PR is not defined.',
            ),
          },
        }),
    })

    expect(card(/task completion rate/i)).toHaveTextContent('0.0%')
    const cost = card(/blended cost per merged pr/i)
    expect(cost).toHaveTextContent(/No merged PRs in this period/i)
    expect(cost).not.toHaveTextContent('$0')
    expect(cost).not.toHaveTextContent(/no activity/i)
  })

  /**
   * A bare `0` leaves a reader unable to tell a wrong filter from a quiet period. Each zero is
   * explained on its own terms, and the two bases differ: merges are placed by when the PR merged,
   * the funnel cohort by when a task started (AC-07.3, contract §2).
   */
  it('explains a zero merged-PR count and a zero funnel cohort separately', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          kpis: { ...dashboard.kpis, mergedPrs: okMetric('0', 'count') },
          funnel: {
            ...dashboard.funnel,
            stages: {
              started: okMetric('0', 'count'),
              completed: okMetric('0', 'count'),
              prOpened: okMetric('0', 'count'),
              prMerged: okMetric('0', 'count'),
            },
            sideExits: { failed: okMetric('0', 'count'), cancelled: okMetric('0', 'count') },
            residual: { inProgress: okMetric('0', 'count') },
          },
        }),
    })

    const merged = card(/merged agent prs/i)
    expect(merged).toHaveTextContent('0')
    expect(merged).toHaveTextContent(/no eligible agent PRs merged during the selected period/i)
    expect(merged).toHaveTextContent(/counted on the day the PR merged/i)

    const funnel = screen.getByRole('region', { name: /outcome funnel/i })
    expect(funnel).toHaveTextContent(/no eligible code-change tasks started during the selected period/i)
    expect(funnel).toHaveTextContent(/placed here by when they started/i)

    // Two explanations, each about its own metric — never one page-wide claim of inactivity.
    expect(screen.queryByText(/^no activity$/i)).not.toBeInTheDocument()
    // Unrelated metrics keep rendering rather than being hidden by the zeros.
    expect(card(/task completion rate/i)).toHaveTextContent('50.0%')
    expect(card(/active seats/i)).toHaveTextContent('2 / 6')
    // The existing Reset control is reused rather than duplicated per zero note.
    expect(screen.getAllByRole('button', { name: /reset filters/i })).toHaveLength(1)
  })

  /** An unavailable metric must not acquire the zero explanation: it has no value at all. */
  it('does not explain an unavailable count as a zero', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          kpis: {
            ...dashboard.kpis,
            mergedPrs: unavailableMetric(
              'missing_data',
              'source_not_covered',
              'The pull_requests data is not fully covered for this period.',
            ),
          },
        }),
    })

    const merged = card(/merged agent prs/i)
    expect(merged).toHaveTextContent(/not fully covered/i)
    expect(merged).not.toHaveTextContent(/no eligible agent PRs merged/i)
  })

  it('renders a positive sub-dollar spend as the indicator, and a real zero as a figure', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          comparison: {
            ...dashboard.comparison,
            rows: [
              {
                ...dashboard.comparison.rows[0]!,
                codeChangeSpend: {
                  state: 'ok',
                  display: display('0', 'usd'),
                  roundsToZero: true,
                },
              },
            ],
          },
        }),
    })

    const table = screen.getByRole('table', { name: /compared with the organisation benchmark/i })
    expect(table).toHaveTextContent('<$1')
  })

  /** Contract §5.3: under a filter the count stays, the ratio does not (AC-07.5). */
  it('keeps the active-seat count and marks utilisation unavailable under a filter', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...withSelection(dashboard, { teamId: TEAM_PAYMENTS }),
          kpis: {
            ...dashboard.kpis,
            seats: {
              ...dashboard.kpis.seats,
              utilisation: unavailableMetric(
                'unavailable_for_scope',
                'seat_allocation_not_defined_for_scope',
                'Seat allocation is not defined for a team or repository scope, so utilisation is not shown for this selection.',
              ),
            },
          },
        }),
    })

    const seats = card(/active seats/i)
    expect(seats).toHaveTextContent('2 / 6')
    expect(seats).toHaveTextContent(/seat allocation is not defined/i)
    expect(seats).not.toHaveTextContent('33.3%')
  })
})

describe('loading and failure', () => {
  /** A pending selection must never show the previous one's numbers (AC-07.1). */
  it('hides superseded values while a new selection is pending', async () => {
    let release: ((response: Response) => void) | undefined
    // Routed on the requested range, not on a call count: the page legitimately issues one
    // dashboard request before coverage resolves the default dates and another after.
    const { user } = await renderDashboard({
      dashboard: (url) =>
        url.includes('from=2026-08-25')
          ? new Promise<Response>((resolve) => {
              release = resolve
            })
          : jsonResponse(dashboard),
    })
    expect(card(/merged agent prs/i)).toHaveTextContent('1')

    await user.click(screen.getByRole('button', { name: /^7 days$/i }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/loading results/i))
    expect(screen.queryByRole('heading', { name: /merged agent prs/i })).not.toBeInTheDocument()

    release?.(jsonResponse({ ...dashboard, kpis: { ...dashboard.kpis, mergedPrs: okMetric('9', 'count') } }))
    await waitFor(() => expect(card(/merged agent prs/i)).toHaveTextContent('9'))
  })

  /** A rejected selection is one failure with one cause, and no metrics beneath it (AC-02.7). */
  it('reports a rejected date range specifically and renders no metrics', async () => {
    await renderDashboard({
      dashboard: () => problem('urn:fleet:problem:reversed-date-range'),
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/start date must not be after the end date/i)
    expect(alert).not.toHaveTextContent('internal detail')
    expect(screen.queryByRole('heading', { name: /merged agent prs/i })).not.toBeInTheDocument()
    // The header and filters stay usable so the selection can be corrected.
    expect(screen.getByRole('button', { name: /reset filters/i })).toBeEnabled()
  })

  /** An unknown filter must offer Reset and never render unfiltered results (AC-02.8). */
  it('offers Reset for an unknown filter and shows no results', async () => {
    window.history.replaceState(null, '', '/?teamId=missing-team')
    await renderDashboard({
      dashboard: (url) =>
        url.includes('missing-team')
          ? problem('urn:fleet:problem:unknown-filter')
          : jsonResponse(dashboard),
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/not part of this organisation/i)
    expect(within(alert).getByRole('button', { name: /reset filters/i })).toBeVisible()
    expect(screen.queryByRole('heading', { name: /merged agent prs/i })).not.toBeInTheDocument()
  })

  /** Retrying must keep the current filters and URL (AC-07.7). */
  it('retries a recoverable failure without changing the selection', async () => {
    let failing = true
    const { user, calls } = await renderDashboard({
      dashboard: () => (failing ? jsonResponse({ broken: true }, 500) : jsonResponse(dashboard)),
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/something went wrong/i)
    const urlBefore = window.location.search

    failing = false
    await user.click(within(alert).getByRole('button', { name: /try again/i }))

    await waitFor(() => expect(card(/merged agent prs/i)).toHaveTextContent('1'))
    expect(window.location.search).toBe(urlBefore)
    expect(calls[calls.length - 1]).toContain(urlBefore.replace('?', ''))
  })

  /**
   * One request failed, not several endpoints. A source missing *inside* a valid response leaves
   * everything that does not depend on it rendering (AC-07.6).
   */
  it('marks only the dependent values unavailable when a source is missing', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          kpis: {
            ...dashboard.kpis,
            mergedPrs: unavailableMetric(
              'missing_data',
              'source_not_covered',
              'The pull_requests data is not fully covered for this period, so this value is unavailable.',
            ),
          },
          trends: {
            ...dashboard.trends,
            mergedPrsPerDay: {
              state: 'missing_data',
              unit: 'count',
              reasonCode: 'source_not_covered',
              reason: 'The pull_requests data is not fully covered for this period.',
              points: [],
            },
          },
        }),
    })

    expect(card(/merged agent prs/i)).toHaveTextContent(/pull_requests data is not fully covered/i)
    // Unrelated cards keep their real values rather than reading as zero.
    expect(card(/task completion rate/i)).toHaveTextContent('50.0%')
    expect(card(/active seats/i)).toHaveTextContent('2 / 6')
    // No page-wide "no activity" claim anywhere.
    expect(screen.queryByText(/no activity/i)).not.toBeInTheDocument()
  })
})

describe('the funnel', () => {
  it('renders the supplied counts, side exits, residual and cutoff', async () => {
    await renderDashboard()

    const funnel = screen.getByRole('region', { name: /outcome funnel/i })
    expect(funnel).toHaveTextContent(/outcomes followed through 31 Aug 2026/i)
    expect(within(funnel).getByText('Tasks started').closest('li')).toHaveTextContent('4')
    expect(within(funnel).getByText('PR merged').closest('li')).toHaveTextContent('1')
    // Side exits and the residual are separate groups, not stages.
    expect(within(funnel).getByRole('heading', { name: /side exits/i })).toBeVisible()
    expect(within(funnel).getByRole('heading', { name: /still running/i })).toBeVisible()
    expect(funnel).toHaveTextContent('1 completed + 1 failed + 1 cancelled + 1 in progress = 4 started')
  })

  it('explains that the funnel and the cards need not reconcile, with no maturity threshold', async () => {
    await renderDashboard()

    const funnel = screen.getByRole('region', { name: /outcome funnel/i })
    expect(within(funnel).getByText(/why the funnel and the cards differ/i)).toBeVisible()
    expect(funnel).toHaveTextContent(/not expected to reconcile/i)
    expect(funnel).toHaveTextContent(/less time to reach a merge/i)
  })

  /** A missing PR stage is unavailable, never derived and never zero (AC-03.1). */
  it('leaves a missing PR stage unavailable rather than substituting zero', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          funnel: {
            ...dashboard.funnel,
            stages: {
              ...dashboard.funnel.stages,
              prOpened: unavailableMetric(
                'missing_data',
                'source_not_covered',
                'The pull_requests data is not fully covered for this period.',
              ),
              prMerged: unavailableMetric(
                'missing_data',
                'source_not_covered',
                'The pull_requests data is not fully covered for this period.',
              ),
            },
          },
        }),
    })

    const funnel = screen.getByRole('region', { name: /outcome funnel/i })
    const prMerged = within(funnel).getByText('PR merged').closest('li')
    expect(prMerged).toHaveTextContent(/not fully covered/i)
    expect(prMerged).not.toHaveTextContent('0')
    // Task stages are unaffected, and the identity line is withheld rather than shown incomplete.
    expect(within(funnel).getByText('Tasks started').closest('li')).toHaveTextContent('4')
  })
})

describe('the trends', () => {
  it('renders two separate charts with their own units and labels', async () => {
    await renderDashboard()

    const trends = screen.getByRole('region', { name: /daily trends/i })
    expect(trends).toHaveTextContent(/PRs \/ day · code-change tasks only/i)
    expect(trends).toHaveTextContent(/USD \/ day · all task types/i)
  })

  /** Every plotted point, including zero days, is available as text (AC-04.2, AC-04.3). */
  it('offers a table equivalent containing every supplied point', async () => {
    const { user } = await renderDashboard()

    await user.click(screen.getByText(/view data — merged agent prs per day/i))
    const table = screen.getByRole('table', { name: /merged agent prs per day/i })

    expect(within(table).getByRole('rowheader', { name: '2026-08-02' })).toBeVisible()
    expect(within(table).getByRole('rowheader', { name: '2026-08-03' })).toBeVisible()
    expect(within(table).getByRole('rowheader', { name: '2026-08-04' })).toBeVisible()
    // The zero day is a plotted zero, not a gap.
    expect(within(table).getByRole('rowheader', { name: '2026-08-02' }).closest('tr'))
      .toHaveTextContent('0')

    await user.click(screen.getByText(/view data — total agent spend per day/i))
    const spend = screen.getByRole('table', { name: /total agent spend per day/i })
    expect(spend).toHaveTextContent('$1.50')
    expect(spend).toHaveTextContent('$21.50')
  })

  /** An unavailable series and an all-zero series are different claims (contract §5.1). */
  it('distinguishes a missing source from an all-zero series', async () => {
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          trends: {
            mergedPrsPerDay: {
              state: 'missing_data',
              unit: 'count',
              reasonCode: 'source_not_covered',
              reason: 'The pull_requests data is not fully covered for this period.',
              points: [],
            },
            spendPerDay: {
              state: 'ok',
              unit: 'usdCents',
              points: [
                { date: '2026-08-02', spendCents: 0 },
                { date: '2026-08-03', spendCents: 0 },
              ],
            },
          },
        }),
    })

    const trends = screen.getByRole('region', { name: /daily trends/i })
    expect(trends).toHaveTextContent(/no days are plotted, because this series is unknown/i)
    // The all-zero series still plots its days and stays readable.
    expect(within(trends).getByText(/view data — total agent spend per day/i)).toBeVisible()
    expect(screen.queryByText(/view data — merged agent prs per day/i)).not.toBeInTheDocument()
  })
})

describe('the comparison table', () => {
  it('renders the server row order with the benchmark pinned first', async () => {
    await renderDashboard()

    const table = screen.getByRole('table', { name: /compared with the organisation benchmark/i })
    const rowHeaders = within(table).getAllByRole('rowheader')

    expect(rowHeaders[0]).toHaveTextContent(/your organisation/i)
    expect(rowHeaders[1]).toHaveTextContent('Payments')
    expect(rowHeaders[2]).toHaveTextContent('Platform')
  })

  it('explains the benchmark scope without deriving it from the rows', async () => {
    const { user } = await renderDashboard()

    await user.click(screen.getByText(/about these comparisons/i))
    const comparison = screen.getByRole('region', { name: /^comparison/i })

    expect(comparison).toHaveTextContent(/includes the selected team’s own contribution/i)
    expect(comparison).toHaveTextContent(/ignores the team filter/i)
    expect(comparison).toHaveTextContent(/not the sum of the visible rows/i)
    expect(comparison).toHaveTextContent(/selecting or focusing a row does not change the benchmark/i)
  })

  /** An unavailable cell keeps its row visible, with its own explanation (AC-05.4). */
  it('keeps undefined rows visible and preserves known spend beside an unavailable unit cost', async () => {
    await renderDashboard()

    const payments = screen
      .getByRole('table', { name: /compared with the organisation benchmark/i })
      .querySelector('[data-scope-id="' + TEAM_PAYMENTS + '"]')

    expect(payments).toHaveTextContent(/No merged PRs in this period/i)
    expect(payments).toHaveTextContent('$23')      // the row's own spend is still shown
    expect(payments).toHaveTextContent(/needs 20 completed or failed/i)
  })

  it('labels row deltas against the organisation benchmark, not the previous period', async () => {
    // A row whose comparison actually computed: a suppressed one shows its reason and no basis,
    // so it could not demonstrate the label either way.
    await renderDashboard({
      dashboard: () =>
        jsonResponse({
          ...dashboard,
          comparison: {
            ...dashboard.comparison,
            rows: [
              {
                ...dashboard.comparison.rows[0]!,
                taskCompletionRate: okMetric(
                  '91.2',
                  'percent',
                  okComparison('percentagePoints', '3.2', 'percentagePoints'),
                ),
              },
            ],
          },
        }),
    })

    const table = screen.getByRole('table', { name: /compared with the organisation benchmark/i })
    const payments = table.querySelector(`[data-scope-id="${TEAM_PAYMENTS}"]`)

    expect(payments).toHaveTextContent('vs organisation benchmark')
    expect(payments).not.toHaveTextContent('vs previous period')
    // The server's own value and delta are untouched by the label change.
    expect(payments).toHaveTextContent('91.2%')
    expect(payments).toHaveTextContent('+3.2 pp')
  })

  /** A suppressed row comparison still shows its reason, and no basis label alongside it. */
  it('keeps a suppressed row reason without a basis label', async () => {
    await renderDashboard()

    const payments = screen
      .getByRole('table', { name: /compared with the organisation benchmark/i })
      .querySelector(`[data-scope-id="${TEAM_PAYMENTS}"]`)

    expect(payments).toHaveTextContent(/needs 20 completed or failed/i)
    expect(payments).not.toHaveTextContent('vs previous period')
  })

  /** A benchmark row's own figures carry no delta at all: it is what rows are measured against. */
  it('gives the benchmark row no delta of its own', async () => {
    await renderDashboard()

    const benchmark = screen
      .getByRole('table', { name: /compared with the organisation benchmark/i })
      .querySelector('.data-table__row--benchmark')

    expect(benchmark).not.toHaveTextContent(/vs organisation benchmark/i)
    expect(benchmark).not.toHaveTextContent(/vs previous period/i)
  })

  it('switches grouping without disturbing the dates or filters', async () => {
    window.history.replaceState(
      null,
      '',
      `/?from=2026-08-02&to=2026-08-31&teamId=${TEAM_PAYMENTS}&grouping=teams`,
    )
    const { user, calls } = await renderDashboard()

    await user.click(screen.getByRole('button', { name: /^repositories$/i }))

    await waitFor(() => expect(window.location.search).toContain('grouping=repositories'))
    expect(window.location.search).toContain('from=2026-08-02')
    expect(window.location.search).toContain('to=2026-08-31')
    expect(window.location.search).toContain(`teamId=${TEAM_PAYMENTS}`)
    expect(calls[calls.length - 1]).toContain('grouping=repositories')
  })

  it('scrolls a wide table inside its own labelled region', async () => {
    await renderDashboard()

    const region = screen.getByRole('region', {
      name: /compared with the organisation benchmark/i,
    })
    expect(region).toHaveAttribute('tabindex', '0')
  })
})

describe('filters', () => {
  it('applies a preset and encodes it in the URL', async () => {
    const { user, calls } = await renderDashboard()

    await user.click(screen.getByRole('button', { name: /^7 days$/i }))

    await waitFor(() => expect(window.location.search).toContain('from=2026-08-25'))
    expect(window.location.search).toContain('to=2026-08-31')
    expect(calls[calls.length - 1]).toContain('from=2026-08-25')
  })

  /** Editing one date must not request a half-edited range (AC-02.2). */
  it('treats custom dates as drafts until Apply', async () => {
    const { user, calls } = await renderDashboard()
    const before = calls.length

    const from = screen.getByLabelText(/^from$/i)
    await user.clear(from)
    await user.type(from, '2026-06-01')

    expect(calls.length).toBe(before)
    expect(window.location.search).toContain('from=2026-08-02')
    // The applied label is unchanged: the draft has not been applied.
    expect(within(screen.getByRole('banner')).getByText(/showing/i)).toHaveTextContent(
      '2–31 Aug 2026',
    )

    await user.click(screen.getByRole('button', { name: /^apply$/i }))

    await waitFor(() => expect(window.location.search).toContain('from=2026-06-01'))
    // Both dates travel together, so the untouched end date is still applied.
    expect(window.location.search).toContain('to=2026-08-31')
  })

  it('resets to the default range with no filters', async () => {
    window.history.replaceState(
      null,
      '',
      `/?from=2026-06-01&to=2026-08-31&teamId=${TEAM_PAYMENTS}&repositoryId=${REPO_API}&grouping=repositories`,
    )
    const { user } = await renderDashboard()

    await user.click(screen.getByRole('button', { name: /reset filters/i }))

    await waitFor(() => expect(window.location.search).toContain('from=2026-08-02'))
    expect(window.location.search).not.toContain('teamId')
    expect(window.location.search).not.toContain('repositoryId')
    expect(window.location.search).toContain('grouping=teams')
  })

  it('names both filters in the applied summary', async () => {
    window.history.replaceState(
      null,
      '',
      `/?from=2026-08-02&to=2026-08-31&teamId=${TEAM_PAYMENTS}&repositoryId=${REPO_API}`,
    )
    await renderDashboard()

    expect(screen.getByText(/team: payments/i)).toBeVisible()
    expect(screen.getByText(/repository: repo-api/i)).toBeVisible()
  })
})
