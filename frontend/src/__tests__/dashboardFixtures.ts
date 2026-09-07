/**
 * Small, explicit dashboard responses with already-computed values.
 *
 * Every number here is written by hand, not generated: these fixtures stand in for the API, so
 * deriving them would make the tests agree with a frontend calculation rather than with the
 * contract. They are deliberately far smaller than the M4 dataset — the point is to pin rendering,
 * not to reproduce the generator.
 */
import type {
  Comparison,
  DashboardResponse,
  Display,
  Finding,
  Metric,
  SeatsMetric,
} from '../api/dashboard'
import type { ContextResponse } from '../api/client'

export const TEAM_PAYMENTS = '2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d002'
export const TEAM_PLATFORM = '2a1f0c64-1d3b-4f7a-9c02-5e8b7a10d001'
export const REPO_API = '3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e001'
export const REPO_WEB = '3b2e1d75-2e4c-4a8b-8d13-6f9c8b21e002'

export function display(value: string, unit: Display['unit']): Display {
  return { value, unit }
}

export function okMetric(value: string, unit: Display['unit'], comparison?: Comparison): Metric {
  return { state: 'ok', display: display(value, unit), ...(comparison ? { comparison } : {}) }
}

export function zeroOutcome(value: string, unit: Display['unit']): Metric {
  return { state: 'zero_outcome', display: display(value, unit) }
}

export function unavailableMetric(
  state: 'no_denominator' | 'unavailable_for_scope' | 'missing_data',
  reasonCode: string,
  reason: string,
  comparison?: Comparison,
): Metric {
  return { state, reasonCode, reason, ...(comparison ? { comparison } : {}) }
}

export function okComparison(
  kind: Comparison['kind'],
  value: string,
  unit: Display['unit'],
): Comparison {
  return { kind, state: 'ok', display: display(value, unit) }
}

export function suppressedComparison(
  kind: Comparison['kind'],
  state: Exclude<Comparison['state'], 'ok'>,
  reasonCode: string,
  reason: string,
): Comparison {
  return { kind, state, reasonCode, reason }
}

export const context: ContextResponse = {
  organisationName: 'Northstar Engineering',
  role: 'ADMIN',
  teams: [
    { id: TEAM_PAYMENTS, name: 'Payments' },
    { id: TEAM_PLATFORM, name: 'Platform' },
  ],
  repositories: [
    { id: REPO_API, name: 'repo-api' },
    { id: REPO_WEB, name: 'repo-web' },
  ],
  licensedSeats: 6,
  coverage: {
    dataAvailableFrom: '2026-03-05T00:00:00Z',
    dataThrough: '2026-09-01T00:00:00Z',
    revision: 'demo-1',
  },
}

const seats: SeatsMetric = {
  state: 'ok',
  display: display('2', 'count'),
  licensedSeats: 6,
  comparison: okComparison('absoluteCount', '-1', 'count'),
  utilisation: okMetric('33.3', 'percent'),
}

/**
 * The metrics contract's worked example (§8.1–§8.4): one merged PR, a 100% merge rate whose
 * comparison is gated, $23.00 unit cost, 50% completion and two of six seats.
 */
export const dashboard: DashboardResponse = {
  coverage: {
    dataAvailableFrom: '2026-03-05T00:00:00Z',
    dataThrough: '2026-09-01T00:00:00Z',
    revision: 'demo-1',
  },
  selection: {
    from: '2026-08-02',
    to: '2026-08-31',
    startInclusive: '2026-08-02T00:00:00Z',
    endExclusive: '2026-09-01T00:00:00Z',
    previousFrom: '2026-07-03',
    previousTo: '2026-08-01',
    teamId: null,
    repositoryId: null,
    grouping: 'teams',
    observationCutoff: '2026-08-31',
  },
  coverageWindows: {
    current: {
      startInclusive: '2026-08-02T00:00:00Z',
      endExclusive: '2026-09-01T00:00:00Z',
      incompleteSources: [],
    },
    previousPeriod: {
      startInclusive: '2026-07-03T00:00:00Z',
      endExclusive: '2026-08-02T00:00:00Z',
      incompleteSources: [],
    },
    failureBaseline28d: {
      startInclusive: '2026-07-05T00:00:00Z',
      endExclusive: '2026-08-02T00:00:00Z',
      incompleteSources: [],
    },
    budgetMonthToDate: {
      startInclusive: '2026-08-01T00:00:00Z',
      endExclusive: '2026-09-01T00:00:00Z',
      incompleteSources: [],
    },
    funnelObservation: {
      startInclusive: '2026-08-02T00:00:00Z',
      endExclusive: '2026-09-01T00:00:00Z',
      incompleteSources: [],
    },
  },
  kpis: {
    mergedPrs: okMetric('1', 'count', okComparison('relative', '-50.0', 'percent')),
    terminalMergeRate: {
      state: 'ok',
      display: display('100.0', 'percent'),
      evidence: { mergedPrs: 1, terminalPrs: 1 },
      comparison: suppressedComparison(
        'percentagePoints',
        'insufficient_sample',
        'gate_terminal_prs_15',
        'Comparison needs 15 terminal PRs in both periods; this period had 1 and the previous period had 3.',
      ),
    },
    costPerMergedPr: {
      state: 'ok',
      display: display('23.00', 'usd'),
      evidence: { mergedPrs: 1, codeChangeSpendCents: 2300 },
      comparison: suppressedComparison(
        'absoluteUsd',
        'insufficient_sample',
        'gate_merged_prs_15',
        'Comparison needs 15 merged PRs in both periods; this period had 1 and the previous period had 2.',
      ),
    },
    taskCompletionRate: {
      state: 'ok',
      display: display('50.0', 'percent'),
      evidence: { completedTasks: 1, failedTasks: 1 },
      comparison: suppressedComparison(
        'percentagePoints',
        'insufficient_sample',
        'gate_terminal_tasks_20',
        'Comparison needs 20 completed or failed code-change tasks in both periods; this period had 2 and the previous period had 5.',
      ),
    },
    seats,
  },
  funnel: {
    observationCutoff: '2026-08-31',
    stages: {
      started: okMetric('4', 'count'),
      completed: okMetric('1', 'count'),
      prOpened: okMetric('1', 'count'),
      prMerged: okMetric('1', 'count'),
    },
    sideExits: { failed: okMetric('1', 'count'), cancelled: okMetric('1', 'count') },
    residual: { inProgress: okMetric('1', 'count') },
  },
  trends: {
    mergedPrsPerDay: {
      state: 'ok',
      unit: 'count',
      points: [
        { date: '2026-08-02', value: 0 },
        { date: '2026-08-03', value: 1 },
        { date: '2026-08-04', value: 0 },
      ],
    },
    spendPerDay: {
      state: 'ok',
      unit: 'usdCents',
      points: [
        { date: '2026-08-02', spendCents: 0 },
        { date: '2026-08-03', spendCents: 150 },
        { date: '2026-08-04', spendCents: 2150 },
      ],
    },
  },
  comparison: {
    grouping: 'teams',
    rows: [
      {
        scopeId: TEAM_PAYMENTS,
        scopeName: 'Payments',
        terminalTaskCount: 2,
        taskCompletionRate: okMetric(
          '50.0',
          'percent',
          suppressedComparison(
            'percentagePoints',
            'insufficient_sample',
            'gate_terminal_tasks_20',
            'Comparison with the organisation benchmark needs 20 completed or failed code-change tasks in this row; it has 2.',
          ),
        ),
        terminalMergeRate: unavailableMetric(
          'no_denominator',
          'no_terminal_prs',
          'No PRs reached a terminal state in this period, so a merge rate is not defined.',
        ),
        costPerMergedPr: unavailableMetric(
          'no_denominator',
          'no_merged_prs',
          'No merged PRs in this period, so cost per merged PR is not defined.',
        ),
        codeChangeSpend: {
          state: 'ok',
          display: display('23', 'usd'),
          roundsToZero: false,
          evidence: { codeChangeSpendCents: 2300 },
        },
      },
      {
        scopeId: TEAM_PLATFORM,
        scopeName: 'Platform',
        terminalTaskCount: 0,
        taskCompletionRate: unavailableMetric(
          'no_denominator',
          'no_terminal_tasks',
          'No code-change tasks reached a terminal state in this period, so a completion rate is not defined.',
        ),
        terminalMergeRate: okMetric('100.0', 'percent'),
        costPerMergedPr: okMetric('0.00', 'usd'),
        codeChangeSpend: { state: 'ok', display: display('0', 'usd'), roundsToZero: false },
      },
    ],
    benchmark: {
      scope: {
        teamFilterIgnored: true,
        repositoryFilterApplied: false,
        includesSelectedTeam: true,
        mayIncludeUndisplayedTeams: false,
      },
      taskCompletionRate: okMetric('50.0', 'percent'),
      terminalMergeRate: okMetric('100.0', 'percent'),
      costPerMergedPr: okMetric('23.00', 'usd'),
      codeChangeSpend: { state: 'ok', display: display('23', 'usd'), roundsToZero: false },
    },
  },
  attention: { findings: [], evaluationsCompleted: 5, limits: [] },
}

// --- Findings -------------------------------------------------------------------------------------

/** Budget: its own month, and a link that clears the repository restriction with an explicit null. */
export const budgetFinding: Finding = {
  ruleType: 'budget_risk',
  severity: 'MEDIUM',
  scopeType: 'team',
  scopeId: TEAM_PAYMENTS,
  scopeName: 'Payments',
  evidence: {
    budgetCents: 1000000,
    monthToDateSpendCents: 590000,
    elapsedDays: 14,
    daysInMonth: 31,
    forecast: display('11800', 'usd'),
    overrun: display('18.0', 'percent'),
  },
  magnitude: display('18.0', 'percent'),
  evaluationPeriod: { from: '2026-08-01', to: '2026-08-14' },
  link: {
    section: 'spendTrend',
    from: '2026-08-01',
    to: '2026-08-14',
    teamId: TEAM_PAYMENTS,
    repositoryId: null,
    periodChanged: true,
  },
}

/** Failure spike on a repository: sets its own dimension, says nothing about the team. */
export const failureFinding: Finding = {
  ruleType: 'task_failure_spike',
  severity: 'MEDIUM',
  scopeType: 'repository',
  scopeId: REPO_API,
  scopeName: 'repo-api',
  evidence: {
    currentRate: display('34.0', 'percent'),
    baselineRate: display('22.0', 'percent'),
    thresholdPercentagePoints: display('8.0', 'percentagePoints'),
    failedTasks: 17,
    failureReasons: { agent: 9, platform: 5, policy: 3 },
  },
  magnitude: display('12.0', 'percentagePoints'),
  evaluationPeriod: { from: '2026-08-02', to: '2026-08-31' },
  link: {
    section: 'comparisonTable',
    repositoryId: REPO_API,
    grouping: 'repositories',
    focusRowId: REPO_API,
    periodChanged: false,
  },
}

export function frictionFinding(domain?: string): Finding {
  return {
    ruleType: 'network_policy_friction',
    severity: 'MEDIUM',
    scopeType: 'team',
    scopeId: TEAM_PAYMENTS,
    scopeName: 'Payments',
    evidence: {
      ...(domain === undefined ? {} : { domain }),
      distinctTasks: 6,
      distinctUsers: 4,
      thresholdTasks: 5,
      thresholdUsers: 3,
    },
    magnitude: display('6', 'count'),
    evaluationPeriod: { from: '2026-08-02', to: '2026-08-31' },
    link: {
      section: 'attention',
      teamId: TEAM_PAYMENTS,
      grouping: 'teams',
      periodChanged: false,
    },
  }
}

export function withAttention(
  base: DashboardResponse,
  attention: DashboardResponse['attention'],
): DashboardResponse {
  return { ...base, attention }
}

export function withSelection(
  base: DashboardResponse,
  selection: Partial<DashboardResponse['selection']>,
): DashboardResponse {
  return { ...base, selection: { ...base.selection, ...selection } }
}
