/**
 * Wire types for `GET /api/v1/analytics/dashboard`, and a runtime guard for them.
 *
 * The metric types are deliberately discriminated unions rather than one record with optional
 * fields. The contract's central rule is that a defined value carries a `display` while an
 * unavailable one carries `reasonCode` and `reason` instead — modelling that in the type makes the
 * distinction impossible to lose, and makes it a compile error to read a display the server never
 * sent. That distinction is the product (`.claude/rules/react.md`).
 *
 * The guard checks wire shape and those state/display invariants only. It is not a second analytics
 * calculator: it never re-derives a rate, a delta or a total, and it accepts whatever numbers the
 * server sends provided they are usable as numbers.
 */

export type Display = {
  readonly value: string
  readonly unit: 'count' | 'percent' | 'percentagePoints' | 'usd'
}

/** Exact integer populations behind a metric. Every field is optional: absence is not zero. */
export type Evidence = {
  readonly mergedPrs?: number
  readonly terminalPrs?: number
  readonly completedTasks?: number
  readonly failedTasks?: number
  readonly codeChangeSpendCents?: number
  readonly previousMergedPrs?: number
  readonly previousTerminalPrs?: number
  readonly previousCompletedTasks?: number
  readonly previousFailedTasks?: number
  readonly previousCodeChangeSpendCents?: number
  readonly previousActiveSeats?: number
}

export type ComparisonKind = 'relative' | 'percentagePoints' | 'absoluteUsd' | 'absoluteCount'

export type SuppressedComparisonState =
  | 'unavailable_for_scope'
  | 'missing_data'
  | 'no_baseline'
  | 'no_denominator'
  | 'insufficient_sample'
  | 'undefined_relative'

/** `kind` survives suppression: a card must say which comparison is missing, never substitute one. */
export type Comparison =
  | {
      readonly kind: ComparisonKind
      readonly state: 'ok'
      readonly display: Display
      readonly evidence?: Evidence
    }
  | {
      readonly kind: ComparisonKind
      readonly state: SuppressedComparisonState
      readonly reasonCode: string
      readonly reason: string
      readonly evidence?: Evidence
    }

/** `zero_outcome` is a computed 0, not an absence — it carries a display like `ok` does. */
export type DefinedMetricState = 'ok' | 'zero_outcome'
export type UnavailableMetricState = 'no_denominator' | 'unavailable_for_scope' | 'missing_data'

export type Metric =
  | {
      readonly state: DefinedMetricState
      readonly display: Display
      readonly roundsToZero?: boolean
      readonly evidence?: Evidence
      readonly comparison?: Comparison
    }
  | {
      readonly state: UnavailableMetricState
      readonly reasonCode: string
      readonly reason: string
      readonly evidence?: Evidence
      readonly comparison?: Comparison
    }

export type SeatsMetric = (
  | { readonly state: DefinedMetricState; readonly display: Display }
  | { readonly state: UnavailableMetricState; readonly reasonCode: string; readonly reason: string }
) & {
  readonly licensedSeats: number
  readonly comparison?: Comparison
  readonly utilisation: Metric
}

export type Grouping = 'teams' | 'repositories'

export type Selection = {
  readonly from: string
  readonly to: string
  readonly startInclusive: string
  readonly endExclusive: string
  readonly previousFrom: string
  readonly previousTo: string
  readonly teamId: string | null
  readonly repositoryId: string | null
  readonly grouping: Grouping
  readonly observationCutoff: string
}

export type CoverageWindow = {
  readonly startInclusive: string
  readonly endExclusive: string
  readonly incompleteSources: readonly string[]
}

/** `budgetMonthToDate` is absent when a repository filter leaves no budget to evaluate. */
export type CoverageWindows = {
  readonly current: CoverageWindow
  readonly previousPeriod: CoverageWindow
  readonly failureBaseline28d: CoverageWindow
  readonly budgetMonthToDate?: CoverageWindow
  readonly funnelObservation: CoverageWindow
}

export type Kpis = {
  readonly mergedPrs: Metric
  readonly terminalMergeRate: Metric
  readonly costPerMergedPr: Metric
  readonly taskCompletionRate: Metric
  readonly seats: SeatsMetric
}

export type Funnel = {
  readonly observationCutoff: string
  readonly stages: {
    readonly started: Metric
    readonly completed: Metric
    readonly prOpened: Metric
    readonly prMerged: Metric
  }
  readonly sideExits: { readonly failed: Metric; readonly cancelled: Metric }
  readonly residual: { readonly inProgress: Metric }
}

export type CountPoint = { readonly date: string; readonly value: number }
export type SpendPoint = { readonly date: string; readonly spendCents: number }

/**
 * An unavailable series carries no points at all. A run of zeros would assert that nothing
 * happened; an empty series admits the data is unknown (contract §5.1).
 */
export type TrendSeries<Unit extends string, Point> =
  | { readonly state: 'ok'; readonly unit: Unit; readonly points: readonly Point[] }
  | {
      readonly state: 'missing_data' | 'unavailable_for_scope'
      readonly unit: Unit
      readonly reasonCode: string
      readonly reason: string
      readonly points: readonly Point[]
    }

export type Trends = {
  readonly mergedPrsPerDay: TrendSeries<'count', CountPoint>
  readonly spendPerDay: TrendSeries<'usdCents', SpendPoint>
}

export type ComparisonRow = {
  readonly scopeId: string
  readonly scopeName: string
  readonly terminalTaskCount: number
  readonly taskCompletionRate: Metric
  readonly terminalMergeRate: Metric
  readonly costPerMergedPr: Metric
  readonly codeChangeSpend: Metric
}

export type BenchmarkScope = {
  readonly teamFilterIgnored: boolean
  readonly repositoryFilterApplied: boolean
  readonly includesSelectedTeam: boolean
  readonly mayIncludeUndisplayedTeams: boolean
}

export type Benchmark = {
  readonly scope: BenchmarkScope
  readonly taskCompletionRate: Metric
  readonly terminalMergeRate: Metric
  readonly costPerMergedPr: Metric
  readonly codeChangeSpend: Metric
}

export type ComparisonTable = {
  readonly grouping: Grouping
  readonly rows: readonly ComparisonRow[]
  readonly benchmark: Benchmark
}

export type FindingSection = 'spendTrend' | 'comparisonTable' | 'attention'

/**
 * A tri-state patch on the current selection: an **absent** key means preserve, a key present with
 * **null** means clear, and a key with a value means set.
 *
 * The optional-and-nullable types are load-bearing. `teamId?: string | null` is the only way to
 * express all three states, and code reading it must use `Object.hasOwn` rather than a truthiness
 * or `!== undefined` check — otherwise a budget finding silently stops clearing the repository
 * filter, which lands the user in a scope where budgets are not evaluated at all.
 */
export type FindingLink = {
  readonly section: FindingSection
  readonly from?: string
  readonly to?: string
  readonly teamId?: string | null
  readonly repositoryId?: string | null
  readonly grouping?: Grouping
  readonly focusRowId?: string
  readonly periodChanged?: boolean
}

export type FailureReasonGroups = {
  readonly agent: number
  readonly platform: number
  readonly policy: number
}

/** `domain` reaches an ADMIN only; for a VIEWER the key is absent and the counts are identical. */
export type FindingEvidence = {
  readonly domain?: string
  readonly failedTasks?: number
  readonly failureReasons?: FailureReasonGroups
  readonly distinctTasks?: number
  readonly distinctUsers?: number
  readonly thresholdTasks?: number
  readonly thresholdUsers?: number
  readonly currentRate?: Display
  readonly baselineRate?: Display
  readonly thresholdPercentagePoints?: Display
  readonly budgetCents?: number
  readonly monthToDateSpendCents?: number
  readonly elapsedDays?: number
  readonly daysInMonth?: number
  readonly forecast?: Display
  readonly overrun?: Display
}

export type RuleType =
  | 'budget_risk'
  | 'task_failure_spike'
  | 'merge_rate_decline'
  | 'network_policy_friction'

export type ScopeType = 'organisation' | 'team' | 'repository'

export type Finding = {
  readonly ruleType: RuleType
  readonly severity: 'HIGH' | 'MEDIUM'
  readonly scopeType: ScopeType
  readonly scopeId: string | null
  readonly scopeName: string
  readonly evidence: FindingEvidence
  readonly magnitude: Display
  readonly evaluationPeriod: { readonly from: string; readonly to: string }
  readonly link: FindingLink
}

export type EvaluationLimit = {
  readonly ruleType: RuleType
  readonly scopeType: ScopeType
  readonly scopeId?: string | null
  readonly scopeName?: string
  readonly state:
    | 'not_evaluated'
    | 'insufficient_history'
    | 'invalid_budget_configuration'
    | 'unavailable_for_scope'
  readonly reasonCode: string
  readonly reason: string
}

export type Attention = {
  readonly findings: readonly Finding[]
  readonly evaluationsCompleted: number
  readonly limits: readonly EvaluationLimit[]
}

export type DashboardCoverage = {
  readonly dataAvailableFrom: string
  readonly dataThrough: string
  readonly revision: string
}

export type DashboardResponse = {
  readonly coverage: DashboardCoverage
  readonly selection: Selection
  readonly coverageWindows: CoverageWindows
  readonly kpis: Kpis
  readonly funnel: Funnel
  readonly trends: Trends
  readonly comparison: ComparisonTable
  readonly attention: Attention
}

// --- Runtime validation ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

/**
 * Every integer the UI reads is a count or a cent total that will be formatted, scaled or summed
 * into chart geometry. A non-finite or fractionally-precise value would render as `NaN` or as a
 * silently wrong figure, so it is rejected at the boundary rather than downstream.
 */
function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value)
}

function optional<T>(value: unknown, guard: (v: unknown) => v is T): boolean {
  return value === undefined || guard(value)
}

function isOneOf<T extends string>(values: readonly T[]) {
  return (value: unknown): value is T => typeof value === 'string' && values.includes(value as T)
}

const isDisplayUnit = isOneOf(['count', 'percent', 'percentagePoints', 'usd'] as const)
const isGrouping = isOneOf(['teams', 'repositories'] as const)
const isScopeType = isOneOf(['organisation', 'team', 'repository'] as const)
const isRuleType = isOneOf([
  'budget_risk',
  'task_failure_spike',
  'merge_rate_decline',
  'network_policy_friction',
] as const)
const isDefinedMetricState = isOneOf(['ok', 'zero_outcome'] as const)
const isUnavailableMetricState = isOneOf([
  'no_denominator',
  'unavailable_for_scope',
  'missing_data',
] as const)
const isComparisonKind = isOneOf([
  'relative',
  'percentagePoints',
  'absoluteUsd',
  'absoluteCount',
] as const)
const isSuppressedComparisonState = isOneOf([
  'unavailable_for_scope',
  'missing_data',
  'no_baseline',
  'no_denominator',
  'insufficient_sample',
  'undefined_relative',
] as const)

/**
 * A plain decimal, optionally negative, and nothing else.
 *
 * The string is kept verbatim — its scale is part of the answer — so this is the only place the
 * value is checked at all. A blank, `"NaN"`, `"Infinity"` or `"abc"` would otherwise flow straight
 * to the screen as a headline figure, and digit grouping would render it as a plausible-looking
 * `NaN` or empty card rather than an error. Exponent forms are rejected too: the server produces
 * `BigDecimal.toPlainString()`, which never emits them, so one arriving means something upstream
 * changed shape.
 */
const DECIMAL_DISPLAY = /^-?\d+(\.\d+)?$/

function isDisplay(value: unknown): value is Display {
  return (
    isRecord(value) &&
    typeof value.value === 'string' &&
    DECIMAL_DISPLAY.test(value.value) &&
    isDisplayUnit(value.unit)
  )
}

const EVIDENCE_COUNTS = [
  'mergedPrs',
  'terminalPrs',
  'completedTasks',
  'failedTasks',
  'codeChangeSpendCents',
  'previousMergedPrs',
  'previousTerminalPrs',
  'previousCompletedTasks',
  'previousFailedTasks',
  'previousCodeChangeSpendCents',
  'previousActiveSeats',
] as const

function isEvidence(value: unknown): value is Evidence {
  return isRecord(value) && EVIDENCE_COUNTS.every((name) => optional(value[name], isCount))
}

/** Rejects the combinations the schema forbids: a defined state without a display, or vice versa. */
function isComparison(value: unknown): value is Comparison {
  if (!isRecord(value) || !isComparisonKind(value.kind)) return false
  if (!optional(value.evidence, isEvidence)) return false
  if (value.state === 'ok') {
    return isDisplay(value.display) && value.reasonCode === undefined
  }
  return (
    isSuppressedComparisonState(value.state) &&
    value.display === undefined &&
    isText(value.reasonCode) &&
    isText(value.reason)
  )
}

function isMetric(value: unknown): value is Metric {
  if (!isRecord(value)) return false
  if (!optional(value.evidence, isEvidence)) return false
  if (!optional(value.comparison, isComparison)) return false
  if (isDefinedMetricState(value.state)) {
    return (
      isDisplay(value.display) &&
      value.reasonCode === undefined &&
      optional(value.roundsToZero, (v): v is boolean => typeof v === 'boolean')
    )
  }
  return (
    isUnavailableMetricState(value.state) &&
    value.display === undefined &&
    isText(value.reasonCode) &&
    isText(value.reason)
  )
}

function isSeatsMetric(value: unknown): value is SeatsMetric {
  if (!isRecord(value)) return false
  if (!isCount(value.licensedSeats) || !isMetric(value.utilisation)) return false
  if (!optional(value.comparison, isComparison)) return false
  if (isDefinedMetricState(value.state)) return isDisplay(value.display)
  return (
    isUnavailableMetricState(value.state) &&
    value.display === undefined &&
    isText(value.reasonCode) &&
    isText(value.reason)
  )
}

function isNullableId(value: unknown): value is string | null {
  return value === null || isText(value)
}

function isSelection(value: unknown): value is Selection {
  return (
    isRecord(value) &&
    isText(value.from) &&
    isText(value.to) &&
    isText(value.startInclusive) &&
    isText(value.endExclusive) &&
    isText(value.previousFrom) &&
    isText(value.previousTo) &&
    isNullableId(value.teamId) &&
    isNullableId(value.repositoryId) &&
    isGrouping(value.grouping) &&
    isText(value.observationCutoff)
  )
}

function isCoverage(value: unknown): value is DashboardCoverage {
  return (
    isRecord(value) &&
    isText(value.dataAvailableFrom) &&
    isText(value.dataThrough) &&
    isText(value.revision)
  )
}

function isCoverageWindow(value: unknown): value is CoverageWindow {
  return (
    isRecord(value) &&
    isText(value.startInclusive) &&
    isText(value.endExclusive) &&
    Array.isArray(value.incompleteSources) &&
    value.incompleteSources.every((source) => typeof source === 'string')
  )
}

function isCoverageWindows(value: unknown): value is CoverageWindows {
  return (
    isRecord(value) &&
    isCoverageWindow(value.current) &&
    isCoverageWindow(value.previousPeriod) &&
    isCoverageWindow(value.failureBaseline28d) &&
    isCoverageWindow(value.funnelObservation) &&
    optional(value.budgetMonthToDate, isCoverageWindow)
  )
}

function isKpis(value: unknown): value is Kpis {
  return (
    isRecord(value) &&
    isMetric(value.mergedPrs) &&
    isMetric(value.terminalMergeRate) &&
    isMetric(value.costPerMergedPr) &&
    isMetric(value.taskCompletionRate) &&
    isSeatsMetric(value.seats)
  )
}

function isFunnel(value: unknown): value is Funnel {
  if (!isRecord(value) || !isText(value.observationCutoff)) return false
  const { stages, sideExits, residual } = value
  return (
    isRecord(stages) &&
    isMetric(stages.started) &&
    isMetric(stages.completed) &&
    isMetric(stages.prOpened) &&
    isMetric(stages.prMerged) &&
    isRecord(sideExits) &&
    isMetric(sideExits.failed) &&
    isMetric(sideExits.cancelled) &&
    isRecord(residual) &&
    isMetric(residual.inProgress)
  )
}

function isSeries(
  value: unknown,
  unit: string,
  isPoint: (point: unknown) => boolean,
): boolean {
  if (!isRecord(value) || value.unit !== unit) return false
  if (!Array.isArray(value.points) || !value.points.every(isPoint)) return false
  if (value.state === 'ok') return true
  return (
    (value.state === 'missing_data' || value.state === 'unavailable_for_scope') &&
    isText(value.reasonCode) &&
    isText(value.reason)
  )
}

function isCountPoint(value: unknown): boolean {
  return isRecord(value) && isText(value.date) && isCount(value.value)
}

function isSpendPoint(value: unknown): boolean {
  return isRecord(value) && isText(value.date) && isCount(value.spendCents)
}

function isTrends(value: unknown): value is Trends {
  return (
    isRecord(value) &&
    isSeries(value.mergedPrsPerDay, 'count', isCountPoint) &&
    isSeries(value.spendPerDay, 'usdCents', isSpendPoint)
  )
}

function isComparisonRow(value: unknown): value is ComparisonRow {
  return (
    isRecord(value) &&
    isText(value.scopeId) &&
    typeof value.scopeName === 'string' &&
    isCount(value.terminalTaskCount) &&
    isMetric(value.taskCompletionRate) &&
    isMetric(value.terminalMergeRate) &&
    isMetric(value.costPerMergedPr) &&
    isMetric(value.codeChangeSpend)
  )
}

function isBenchmark(value: unknown): value is Benchmark {
  if (!isRecord(value) || !isRecord(value.scope)) return false
  const scope = value.scope
  const flags = [
    'teamFilterIgnored',
    'repositoryFilterApplied',
    'includesSelectedTeam',
    'mayIncludeUndisplayedTeams',
  ] as const
  return (
    flags.every((flag) => typeof scope[flag] === 'boolean') &&
    isMetric(value.taskCompletionRate) &&
    isMetric(value.terminalMergeRate) &&
    isMetric(value.costPerMergedPr) &&
    isMetric(value.codeChangeSpend)
  )
}

function isComparisonTable(value: unknown): value is ComparisonTable {
  return (
    isRecord(value) &&
    isGrouping(value.grouping) &&
    Array.isArray(value.rows) &&
    value.rows.every(isComparisonRow) &&
    isBenchmark(value.benchmark)
  )
}

/**
 * Validated without normalising: an absent key stays absent and an explicit null stays null, so the
 * tri-state survives. Rewriting this object into a fully-populated record would destroy the very
 * distinction the patch depends on.
 */
function isFindingLink(value: unknown): value is FindingLink {
  if (!isRecord(value)) return false
  if (!isOneOf(['spendTrend', 'comparisonTable', 'attention'] as const)(value.section)) return false
  return (
    optional(value.from, isText) &&
    optional(value.to, isText) &&
    (value.teamId === undefined || isNullableId(value.teamId)) &&
    (value.repositoryId === undefined || isNullableId(value.repositoryId)) &&
    optional(value.grouping, isGrouping) &&
    optional(value.focusRowId, isText) &&
    optional(value.periodChanged, (v): v is boolean => typeof v === 'boolean')
  )
}

function isFindingEvidence(value: unknown): value is FindingEvidence {
  if (!isRecord(value)) return false
  const counts = [
    'failedTasks',
    'distinctTasks',
    'distinctUsers',
    'thresholdTasks',
    'thresholdUsers',
    'budgetCents',
    'monthToDateSpendCents',
    'elapsedDays',
    'daysInMonth',
  ] as const
  const displays = ['currentRate', 'baselineRate', 'thresholdPercentagePoints', 'forecast', 'overrun'] as const
  const groups = value.failureReasons
  return (
    optional(value.domain, isText) &&
    counts.every((name) => optional(value[name], isCount)) &&
    displays.every((name) => optional(value[name], isDisplay)) &&
    (groups === undefined ||
      (isRecord(groups) && isCount(groups.agent) && isCount(groups.platform) && isCount(groups.policy)))
  )
}

function isFinding(value: unknown): value is Finding {
  if (!isRecord(value)) return false
  const period = value.evaluationPeriod
  return (
    isRuleType(value.ruleType) &&
    isOneOf(['HIGH', 'MEDIUM'] as const)(value.severity) &&
    isScopeType(value.scopeType) &&
    isNullableId(value.scopeId) &&
    typeof value.scopeName === 'string' &&
    isFindingEvidence(value.evidence) &&
    isDisplay(value.magnitude) &&
    isRecord(period) &&
    isText(period.from) &&
    isText(period.to) &&
    isFindingLink(value.link)
  )
}

function isEvaluationLimit(value: unknown): value is EvaluationLimit {
  return (
    isRecord(value) &&
    isRuleType(value.ruleType) &&
    isScopeType(value.scopeType) &&
    (value.scopeId === undefined || isNullableId(value.scopeId)) &&
    optional(value.scopeName, (v): v is string => typeof v === 'string') &&
    isOneOf([
      'not_evaluated',
      'insufficient_history',
      'invalid_budget_configuration',
      'unavailable_for_scope',
    ] as const)(value.state) &&
    isText(value.reasonCode) &&
    isText(value.reason)
  )
}

function isAttention(value: unknown): value is Attention {
  return (
    isRecord(value) &&
    Array.isArray(value.findings) &&
    value.findings.every(isFinding) &&
    isCount(value.evaluationsCompleted) &&
    Array.isArray(value.limits) &&
    value.limits.every(isEvaluationLimit)
  )
}

export function isDashboardResponse(value: unknown): value is DashboardResponse {
  return (
    isRecord(value) &&
    isCoverage(value.coverage) &&
    isSelection(value.selection) &&
    isCoverageWindows(value.coverageWindows) &&
    isKpis(value.kpis) &&
    isFunnel(value.funnel) &&
    isTrends(value.trends) &&
    isComparisonTable(value.comparison) &&
    isAttention(value.attention)
  )
}

/** True when the metric carries a value to render — `zero_outcome` included, since 0 is a result. */
export function hasDisplay(
  metric: Metric | SeatsMetric,
): metric is Extract<Metric, { state: DefinedMetricState }> {
  return metric.state === 'ok' || metric.state === 'zero_outcome'
}

export function comparisonHasDisplay(
  comparison: Comparison,
): comparison is Extract<Comparison, { state: 'ok' }> {
  return comparison.state === 'ok'
}
