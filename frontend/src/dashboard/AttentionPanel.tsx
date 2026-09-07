/**
 * The needs-attention panel: what the configured rules found, and what they could not evaluate.
 *
 * The ordering, severity and cap are the server's. Nothing here reranks, recomputes or filters —
 * the panel renders the findings it is given, in the order it is given them (AC-06.1).
 *
 * The five states of AC-06.3 are selected from three server-supplied facts: how many findings there
 * are, how many evaluations completed, and how many could not. None of them may read as a claim of
 * health: "no alerts triggered" and "alerts could not be evaluated" are different statements, and
 * the second must never be dressed as the first.
 */
import type { Attention, EvaluationLimit, Finding, RuleType } from '../api/dashboard'
import type { Role } from '../api/client'
import { useReveal } from './DashboardPage'
import { formatCount, formatDisplay, formatUtcRange } from './presentation'

const RULE_LABEL: Record<RuleType, string> = {
  budget_risk: 'budget',
  task_failure_spike: 'failures',
  merge_rate_decline: 'merge rate',
  network_policy_friction: 'blocked network access',
}

export function AttentionPanel({
  attention,
  role,
  recalculated,
  revealToken,
  onFollow,
}: {
  readonly attention: Attention
  readonly role: Role
  /** True only once a followed friction link's destination response is current. */
  readonly recalculated: boolean
  /** Reveal the destination panel even when recomputation returns no findings (AC-06.7). */
  readonly revealToken: string | null
  readonly onFollow: (finding: Finding) => void
}) {
  const { findings, evaluationsCompleted, limits } = attention
  const hasFindings = findings.length > 0
  const hasLimits = limits.length > 0

  const reveal = useReveal(revealToken, true)

  return (
    <section
      ref={reveal}
      tabIndex={-1}
      className="panel attention"
      aria-labelledby="attention-heading"
      id="attention"
    >
      <div className="panel__head panel__head--split">
        <div>
          <h2 className="panel__title" id="attention-heading">
            Needs attention
          </h2>
          <p className="panel__subtitle">
            Up to three, ranked · every one calculated from the data on this page.
          </p>
        </div>
      </div>

      {recalculated && (
        <p className="notice" role="status">
          Findings recalculated for the selected filters.
        </p>
      )}

      {hasFindings ? (
        <ol className="attention__list">
          {findings.map((finding, index) => (
            // A local rendering key, not a public identity. Changed evidence resets disclosure
            // state; the index distinguishes otherwise identical redacted findings (at most three).
            <li key={`${JSON.stringify(finding)}:${index}`}>
              <FindingCard finding={finding} role={role} onFollow={onFollow} />
            </li>
          ))}
        </ol>
      ) : (
        <p className="attention__none" role="status">
          {evaluationsCompleted === 0
            ? /* Case (e): nothing reached a verdict, so no claim about health can be made. */
              'Alerts could not be evaluated for this selection. Nothing below is a statement that everything is fine.'
            : hasLimits
              ? /* Case (d) */ 'No alerts triggered among the rules that could be evaluated.'
              : /* Case (c) */ 'No configured alerts triggered.'}
        </p>
      )}

      {/* Cases (b), (d) and (e): compact, explanatory, and never counted as a finding (AC-06.13). */}
      {hasLimits && <Limits limits={limits} completed={evaluationsCompleted} />}
    </section>
  )
}

function FindingCard({
  finding,
  role,
  onFollow,
}: {
  readonly finding: Finding
  readonly role: Role
  readonly onFollow: (finding: Finding) => void
}) {
  return (
    <article className="finding">
      <div className="finding__body">
        <p className="finding__meta">
          {/* Severity is a word, not a colour (AC-08.6). */}
          <span className={`badge badge--${finding.severity.toLowerCase()}`}>
            {finding.severity}
          </span>{' '}
          <span className="finding__scope">{finding.scopeName}</span>
          <span className="finding__rule"> · {RULE_LABEL[finding.ruleType]}</span>
        </p>

        <h3 className="finding__headline">
          <FindingHeadline finding={finding} role={role} />
        </h3>

        <FindingEvidenceLine finding={finding} role={role} />

        <p className="finding__period">
          Evaluated over{' '}
          {formatUtcRange(finding.evaluationPeriod.from, finding.evaluationPeriod.to)} (UTC)
          {finding.ruleType === 'budget_risk' &&
            ' — a calendar month, independent of the dates selected above.'}
        </p>

        {/* The approved thresholds, stated so the severity is legible. Explanatory copy only: the
            server decides whether a finding fires and at what severity, and this never recomputes
            either (contract §6.1). */}
        {finding.ruleType === 'budget_risk' && (
          <details className="disclosure finding__thresholds">
            <summary>When this is flagged</summary>
            <p>
              A budget is flagged when the month-end forecast runs more than 10% over budget, and
              rated HIGH when it runs more than 20% over. Both are strict: exactly 10% does not
              flag, and exactly 20% stays MEDIUM. The forecast, the overrun and the severity above
              are the server’s own.
            </p>
          </details>
        )}
      </div>

      <div className="finding__action">
        <button type="button" className="button button--link" onClick={() => onFollow(finding)}>
          {finding.ruleType === 'budget_risk'
            ? `Inspect ${finding.scopeName} spend`
            : `Inspect ${finding.scopeName}`}
        </button>
        <p className="finding__action-note">
          <FindingLinkDescription finding={finding} />
        </p>
      </div>
    </article>
  )
}

/**
 * One sentence per rule, built only from supplied values.
 *
 * Optional evidence is rendered only when present. After M3's coverage gating an evidence field can
 * legitimately be absent, and absence is not zero — so a missing count produces a shorter sentence
 * rather than a fabricated number.
 */
function FindingHeadline({ finding, role }: { readonly finding: Finding; readonly role: Role }) {
  const evidence = finding.evidence
  switch (finding.ruleType) {
    case 'budget_risk':
      return (
        <>
          {finding.scopeName} is on course to overspend its budget by{' '}
          {formatDisplay(finding.magnitude)}
          {evidence.forecast !== undefined && <> — forecast {formatDisplay(evidence.forecast)}</>}
          {evidence.budgetCents !== undefined && (
            <> against a {formatDisplay({ value: `${evidence.budgetCents / 100}`, unit: 'usd' })}{' '}
              monthly budget</>
          )}
          .
        </>
      )
    case 'task_failure_spike':
      return (
        <>
          {finding.scopeName} failures rose {formatDisplay(finding.magnitude)}
          {evidence.currentRate !== undefined && (
            <> — {formatDisplay(evidence.currentRate)} of finished tasks</>
          )}
          {evidence.baselineRate !== undefined && (
            <>, up from {formatDisplay(evidence.baselineRate)}</>
          )}
          .
        </>
      )
    case 'merge_rate_decline':
      return (
        <>
          {finding.scopeName} merge rate fell {formatDisplay(finding.magnitude)}
          {evidence.currentRate !== undefined && (
            <> — now {formatDisplay(evidence.currentRate)}</>
          )}
          {evidence.baselineRate !== undefined && (
            <>, down from {formatDisplay(evidence.baselineRate)}</>
          )}
          .
        </>
      )
    case 'network_policy_friction': {
      // Only the counts that were actually supplied. `?? 0` here would have claimed the domain
      // affected nobody, which is the opposite of what an absent count means — and the rule cannot
      // have fired without both counts clearing their thresholds, so a zero would be self-refuting.
      const affected = frictionAffected(evidence.distinctTasks, evidence.distinctUsers)
      // ADMIN may see the domain; a VIEWER's copy names no domain and never reconstructs one.
      return role === 'ADMIN' && evidence.domain !== undefined ? (
        <>
          {evidence.domain} blocked sandbox access {affected} in {finding.scopeName}.
        </>
      ) : (
        <>
          A blocked network destination affected sandbox access {affected} in {finding.scopeName}.
          The destination itself is visible to platform admins only.
        </>
      )
    }
  }
}

/**
 * The affected-population phrase, composed from whatever counts are present.
 *
 * After M3's coverage gating an evidence field can legitimately be absent, and absence is not zero
 * (contract §1.5). A partially-supplied pair therefore yields a shorter sentence rather than a
 * fabricated half.
 */
function frictionAffected(tasks: number | undefined, users: number | undefined): string {
  if (tasks !== undefined && users !== undefined) {
    return `for ${formatCount(tasks)} ${tasks === 1 ? 'task' : 'tasks'} across ${formatCount(users)} ${users === 1 ? 'person' : 'people'}`
  }
  if (tasks !== undefined) {
    return `for ${formatCount(tasks)} ${tasks === 1 ? 'task' : 'tasks'}`
  }
  if (users !== undefined) {
    return `for ${formatCount(users)} ${users === 1 ? 'person' : 'people'}`
  }
  return 'repeatedly, though the affected counts are not available for this period'
}

function FindingEvidenceLine({ finding, role }: { readonly finding: Finding; readonly role: Role }) {
  const evidence = finding.evidence
  return (
    <>
      <p className="finding__evidence">
        {finding.ruleType === 'budget_risk' && evidence.monthToDateSpendCents !== undefined && (
          <>
            {formatDisplay({ value: `${evidence.monthToDateSpendCents / 100}`, unit: 'usd' })} spent
            so far
            {evidence.elapsedDays !== undefined && (
              <> over the {evidence.elapsedDays} complete days of the month</>
            )}
            .
          </>
        )}
        {finding.ruleType === 'task_failure_spike' && (
          <>
            {evidence.failedTasks !== undefined && <>{evidence.failedTasks} tasks failed. </>}
            {evidence.failureReasons !== undefined && (
              <>
                {evidence.failureReasons.agent} from the agent, {evidence.failureReasons.platform}{' '}
                from the platform, {evidence.failureReasons.policy} blocked by policy.{' '}
              </>
            )}
            {evidence.thresholdPercentagePoints !== undefined && (
              <>
                Flagged at {formatDisplay(evidence.thresholdPercentagePoints)} or more.
              </>
            )}
          </>
        )}
        {finding.ruleType === 'merge_rate_decline' &&
          evidence.thresholdPercentagePoints !== undefined && (
            <>Flagged at {formatDisplay(evidence.thresholdPercentagePoints)} or more.</>
          )}
        {finding.ruleType === 'network_policy_friction' && (
          <>
            {evidence.thresholdTasks !== undefined && evidence.thresholdUsers !== undefined && (
              <>
                Flagged at {evidence.thresholdTasks} or more tasks across{' '}
                {evidence.thresholdUsers} or more people, counted across every kind of task.
              </>
            )}
            {role !== 'ADMIN' && ' The counts here are the same ones an admin sees.'}
          </>
        )}
      </p>
    </>
  )
}

function FindingLinkDescription({ finding }: { readonly finding: Finding }) {
  switch (finding.link.section) {
    case 'spendTrend':
      return (
        <>
          Opens the spend trend for the finding’s own month and clears any repository filter, because
          budgets have no repository allocation.
        </>
      )
    case 'comparisonTable':
      return (
        <>
          Keeps these dates and your other filter, and opens the table grouped to match this scope.
        </>
      )
    case 'attention':
      return <>Keeps these dates; findings are recalculated for the narrower scope.</>
  }
}

/**
 * The scopes and rules that reached no verdict.
 *
 * Compact and explanatory, never styled or counted as a finding: it consumes no part of the
 * three-finding cap (AC-06.13). It exists so silence is never mistaken for health.
 */
function Limits({
  limits,
  completed,
}: {
  readonly limits: readonly EvaluationLimit[]
  readonly completed: number
}) {
  return (
    <details className="disclosure attention__limits">
      <summary>
        {limits.length === 1
          ? '1 check could not run'
          : `${limits.length} checks could not run`}
        {completed > 0 && ` · ${completed} ran`}
      </summary>
      <p className="attention__limits-lead">
        A check that could not run does not mean everything is fine.
      </p>
      <ul className="attention__limit-list">
        {limits.map((limit) => (
          <li key={`${limit.ruleType}:${limit.scopeType}:${limit.scopeId ?? 'organisation'}`}>
            <strong>{limit.scopeName ?? 'Your organisation'}</strong>{' '}
            <span className="attention__limit-rule">({RULE_LABEL[limit.ruleType]})</span>{' '}
            {limit.reason}
          </li>
        ))}
      </ul>
    </details>
  )
}
