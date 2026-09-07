import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  PROBLEM,
  fetchDashboard,
  isSelectionProblem,
  isUnknownFilterProblem,
} from '../api/client'
import { isDashboardResponse } from '../api/dashboard'
import { dashboard } from './dashboardFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problem(type: string, status = 400): Response {
  return new Response(
    JSON.stringify({ type, title: 'Bad Request', status, detail: 'server detail text' }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('fetchDashboard', () => {
  it('sends an authenticated GET and forwards the abort signal', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(dashboard))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await fetchDashboard('token-abc', new URLSearchParams({ grouping: 'teams' }), controller.signal)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/analytics/dashboard?grouping=teams')
    expect(init.headers).toEqual({ Authorization: 'Bearer token-abc' })
    expect(init.signal).toBe(controller.signal)
    expect(init.method ?? 'GET').toBe('GET')
  })

  /**
   * A supplied-but-blank date is a different question from an omitted one, and the server answers
   * them differently. Dropping or normalising it here would silently ask for the 30-day default
   * while the user believes their input was used.
   */
  it('transmits parameters verbatim, including a supplied empty value', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(dashboard))
    vi.stubGlobal('fetch', fetchMock)

    const parameters = new URLSearchParams()
    parameters.set('from', '')
    parameters.set('to', '2026-08-31')
    parameters.set('teamId', 'not-a-uuid')
    await fetchDashboard('t', parameters)

    const [url] = fetchMock.mock.calls[0] as [string]
    expect(url).toContain('from=')
    expect(url).toContain('to=2026-08-31')
    expect(url).toContain('teamId=not-a-uuid')
  })

  it('omits the query string entirely when no parameters are applied', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(dashboard))
    vi.stubGlobal('fetch', fetchMock)

    await fetchDashboard('t', new URLSearchParams())

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/analytics/dashboard')
  })

  it('returns the parsed response for a valid body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dashboard)))

    const result = await fetchDashboard('t', new URLSearchParams())

    expect(result.selection.from).toBe('2026-08-02')
    expect(result.attention.evaluationsCompleted).toBe(5)
  })
})

describe('controlled error messages', () => {
  /** Each cause gets its own sentence, so the message names the control the user must change. */
  const cases: ReadonlyArray<readonly [string, RegExp]> = [
    [PROBLEM.invalidDateFormat, /YYYY-MM-DD/i],
    [PROBLEM.reversedDateRange, /start date must not be after/i],
    [PROBLEM.incompleteDateRange, /both a start and an end date/i],
    [PROBLEM.rangeOutsideCoverage, /outside the range this demo dataset reports/i],
    [PROBLEM.unknownFilter, /not part of this organisation/i],
    [PROBLEM.invalidGrouping, /Teams or Repositories/i],
  ]

  it.each(cases)('maps %s to its own message', async (type, expected) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(type)))

    const error = await fetchDashboard('t', new URLSearchParams()).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toMatch(expected)
    expect((error as ApiError).problemType).toBe(type)
    expect(isSelectionProblem(error)).toBe(true)
  })

  it('never echoes the server detail string', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(PROBLEM.reversedDateRange)))

    const error = (await fetchDashboard('t', new URLSearchParams()).catch(
      (e: unknown) => e,
    )) as ApiError

    expect(error.message).not.toContain('server detail text')
  })

  it('distinguishes an unknown filter, which needs Reset, from other selection problems', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(PROBLEM.unknownFilter)))
    const unknownFilter = await fetchDashboard('t', new URLSearchParams()).catch((e: unknown) => e)

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(PROBLEM.reversedDateRange)))
    const reversed = await fetchDashboard('t', new URLSearchParams()).catch((e: unknown) => e)

    expect(isUnknownFilterProblem(unknownFilter)).toBe(true)
    expect(isUnknownFilterProblem(reversed)).toBe(false)
  })

  it('surfaces authentication failure with its status so only that session ends', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(PROBLEM.unauthenticated, 401)))

    const error = (await fetchDashboard('t', new URLSearchParams()).catch(
      (e: unknown) => e,
    )) as ApiError

    expect(error.status).toBe(401)
    expect(error.message).toMatch(/session has ended/i)
    expect(isSelectionProblem(error)).toBe(false)
  })

  /** An unrecognised type must not reach UI branching as though it meant something. */
  it('keeps unknown, malformed and non-JSON failures sanitised and unbranded', async () => {
    const bodies = [
      problem('urn:fleet:problem:something-new', 400),
      new Response('<html>gateway timeout</html>', { status: 504 }),
      new Response('not json at all', { status: 500 }),
      jsonResponse({ nope: true }, 503),
    ]

    for (const body of bodies) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(body))
      const error = (await fetchDashboard('t', new URLSearchParams()).catch(
        (e: unknown) => e,
      )) as ApiError

      expect(error.message).toBe('Something went wrong. Please try again.')
      expect(error.problemType).toBeUndefined()
      expect(isSelectionProblem(error)).toBe(false)
      expect(error.message).not.toContain('gateway')
    }
  })
})

describe('response validation', () => {
  it('rejects a 2xx whose required sections are missing', async () => {
    const { attention, ...withoutAttention } = dashboard
    expect(attention).toBeDefined()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(withoutAttention)))

    await expect(fetchDashboard('t', new URLSearchParams())).rejects.toBeInstanceOf(ApiError)
  })

  /**
   * The state/display invariant is the product: a defined state must carry a value and an
   * unavailable one must carry a reason. A body breaking it would render a blank where a number
   * belongs, or a number the server never computed.
   */
  it('rejects invalid state and display combinations', () => {
    const definedWithoutDisplay = {
      ...dashboard,
      kpis: { ...dashboard.kpis, mergedPrs: { state: 'ok' } },
    }
    const unavailableWithDisplay = {
      ...dashboard,
      kpis: {
        ...dashboard.kpis,
        mergedPrs: {
          state: 'missing_data',
          display: { value: '1', unit: 'count' },
          reasonCode: 'x',
          reason: 'y',
        },
      },
    }
    const unavailableWithoutReason = {
      ...dashboard,
      kpis: { ...dashboard.kpis, mergedPrs: { state: 'missing_data' } },
    }
    const comparisonOkWithoutDisplay = {
      ...dashboard,
      kpis: {
        ...dashboard.kpis,
        mergedPrs: {
          state: 'ok',
          display: { value: '1', unit: 'count' },
          comparison: { kind: 'relative', state: 'ok' },
        },
      },
    }

    expect(isDashboardResponse(definedWithoutDisplay)).toBe(false)
    expect(isDashboardResponse(unavailableWithDisplay)).toBe(false)
    expect(isDashboardResponse(unavailableWithoutReason)).toBe(false)
    expect(isDashboardResponse(comparisonOkWithoutDisplay)).toBe(false)
  })

  it('accepts an unavailable metric that carries no display', () => {
    const unavailable = {
      ...dashboard,
      kpis: {
        ...dashboard.kpis,
        mergedPrs: {
          state: 'missing_data',
          reasonCode: 'source_not_covered',
          reason: 'The pull_requests data is not fully covered for this period.',
        },
      },
    }

    expect(isDashboardResponse(unavailable)).toBe(true)
  })

  it('rejects numeric fields the UI would render or scale', () => {
    const fractionalPoint = {
      ...dashboard,
      trends: {
        ...dashboard.trends,
        spendPerDay: {
          state: 'ok',
          unit: 'usdCents',
          points: [{ date: '2026-08-02', spendCents: 12.5 }],
        },
      },
    }
    const nonNumericSeats = {
      ...dashboard,
      kpis: { ...dashboard.kpis, seats: { ...dashboard.kpis.seats, licensedSeats: '6' } },
    }

    expect(isDashboardResponse(fractionalPoint)).toBe(false)
    expect(isDashboardResponse(nonNumericSeats)).toBe(false)
  })

  /**
   * A display value is served as a string because its scale is meaning — so the string itself is
   * the only thing checked. Left unvalidated, a blank or `"NaN"` would be digit-grouped and
   * rendered as a headline figure, which reads as a real answer rather than an error.
   */
  it.each([
    ['blank', ''],
    ['whitespace', '  '],
    ['not a number', 'abc'],
    ['NaN', 'NaN'],
    ['Infinity', 'Infinity'],
    ['-Infinity', '-Infinity'],
    ['exponent form', '1e3'],
    ['trailing dot', '23.'],
    ['leading dot', '.5'],
    ['thousands separator', '1,204'],
    ['currency symbol', '$23.00'],
    ['percent suffix', '50.0%'],
    ['double negative', '--5'],
  ])('rejects a %s display value', (_name, value) => {
    const broken = {
      ...dashboard,
      kpis: { ...dashboard.kpis, mergedPrs: { state: 'ok', display: { value, unit: 'count' } } },
    }

    expect(isDashboardResponse(broken)).toBe(false)
  })

  /** Valid decimals survive untouched, trailing zeros and negative deltas included. */
  it.each(['0', '0.0', '0.00', '23', '23.00', '1204', '-50.0', '-16.7', '-0.01', '9007199254740991'])(
    'accepts and preserves the display value %s exactly',
    (value) => {
      const body = {
        ...dashboard,
        kpis: { ...dashboard.kpis, mergedPrs: { state: 'ok', display: { value, unit: 'count' } } },
      }

      expect(isDashboardResponse(body)).toBe(true)
      // Preserved as the server sent it, never round-tripped through Number.
      expect(
        (body as { kpis: { mergedPrs: { display: { value: string } } } }).kpis.mergedPrs.display
          .value,
      ).toBe(value)
    },
  )

  it('applies the same validation to comparison and finding display values', () => {
    const brokenComparison = {
      ...dashboard,
      kpis: {
        ...dashboard.kpis,
        mergedPrs: {
          state: 'ok',
          display: { value: '1', unit: 'count' },
          comparison: { kind: 'relative', state: 'ok', display: { value: '', unit: 'percent' } },
        },
      },
    }

    expect(isDashboardResponse(brokenComparison)).toBe(false)
  })

  it('rejects a trend series whose unit does not match its shape', () => {
    const wrongUnit = {
      ...dashboard,
      trends: {
        ...dashboard.trends,
        mergedPrsPerDay: { state: 'ok', unit: 'usdCents', points: [] },
      },
    }

    expect(isDashboardResponse(wrongUnit)).toBe(false)
  })

  /**
   * The tri-state must survive parsing. Normalising absent keys into nulls would make every
   * non-budget link start clearing the filters it was supposed to preserve.
   */
  it('preserves absent, null and present finding-link fields distinctly', async () => {
    const body = {
      ...dashboard,
      attention: {
        findings: [
          {
            id: 'f1',
            ruleType: 'budget_risk',
            severity: 'MEDIUM',
            scopeType: 'organisation',
            scopeId: null,
            scopeName: 'Northstar Engineering',
            evidence: { budgetCents: 100, monthToDateSpendCents: 50 },
            magnitude: { value: '18.0', unit: 'percent' },
            evaluationPeriod: { from: '2026-08-01', to: '2026-08-14' },
            link: {
              section: 'spendTrend',
              from: '2026-08-01',
              teamId: null,
              repositoryId: null,
              periodChanged: true,
            },
          },
        ],
        evaluationsCompleted: 1,
        limits: [],
      },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))

    const result = await fetchDashboard('t', new URLSearchParams())
    const link = result.attention.findings[0]!.link

    expect(Object.hasOwn(link, 'teamId')).toBe(true)
    expect(link.teamId).toBeNull()
    expect(Object.hasOwn(link, 'to')).toBe(false)
    expect(link.from).toBe('2026-08-01')
    expect(Object.hasOwn(link, 'grouping')).toBe(false)
  })

  it('rejects a finding whose scope identifier is neither a string nor null', () => {
    const broken = {
      ...dashboard,
      attention: {
        findings: [{ ...JSON.parse(JSON.stringify(dashboard)).attention, scopeId: 7 }],
        evaluationsCompleted: 1,
        limits: [],
      },
    }

    expect(isDashboardResponse(broken)).toBe(false)
  })
})
