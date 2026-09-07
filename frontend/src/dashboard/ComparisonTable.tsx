/**
 * Teams or repositories against a pooled organisation benchmark.
 *
 * Row order is the server's — busiest first by eligible terminal tasks — and there is deliberately
 * no client sorting or paging to reorder it (AC-05.5). The benchmark is served, never derived from
 * the visible rows: it pools counts across the whole organisation and can include scopes this table
 * is not showing, so summing the rows would produce a different and wrong number (AC-05.2).
 *
 * Undefined and gated cells stay visible with their own explanations, and a row's own spend stays on
 * screen when its unit cost is undefined (AC-05.4).
 */
import { useId } from 'react'
import type { Benchmark, ComparisonRow, ComparisonTable as Table, Grouping } from '../api/dashboard'
import { ComparisonValue, MetricValue } from './MetricValue'
import { useReveal } from './DashboardPage'

export function ComparisonTable({
  comparison,
  onGrouping,
  focusRowId,
  revealToken,
}: {
  readonly comparison: Table
  readonly onGrouping: (grouping: Grouping) => void
  readonly focusRowId: string | null
  /** Non-null when a followed failure or merge finding's destination is this table (AC-06.6). */
  readonly revealToken: string | null
}) {
  const captionId = useId()
  const grouped = comparison.grouping === 'teams' ? 'Teams' : 'Repositories'

  return (
    <section className="panel comparison" aria-labelledby="comparison-heading" id="comparison-table">
      <div className="panel__head panel__head--split">
        <div>
          <h2 className="panel__title" id="comparison-heading">
            Comparison — {grouped.toLowerCase()}
          </h2>
          <p className="panel__subtitle">
            The same three measures, grouped either way. Busiest first, as ordered by the server.
          </p>
        </div>
        <div className="segmented" role="group" aria-label="Table grouping">
          {(['teams', 'repositories'] as const).map((grouping) => (
            <button
              key={grouping}
              type="button"
              className="button button--segment"
              aria-pressed={comparison.grouping === grouping}
              onClick={() => onGrouping(grouping)}
            >
              {grouping === 'teams' ? 'Teams' : 'Repositories'}
            </button>
          ))}
        </div>
      </div>

      {/* A table wider than the viewport scrolls inside its own labelled, focusable region rather
          than pushing the page sideways (AC-08.4). */}
      <div className="table-scroll" tabIndex={0} role="region" aria-labelledby={captionId}>
        <table className="data-table data-table--comparison">
          <caption id={captionId}>
            {grouped} compared with the organisation benchmark. Scroll horizontally for all columns.
          </caption>
          <thead>
            <tr>
              <th scope="col">{comparison.grouping === 'teams' ? 'Team' : 'Repository'}</th>
              <th scope="col">Task completion rate</th>
              <th scope="col">Terminal merge rate</th>
              <th scope="col">Cost per merged PR</th>
              <th scope="col">Code-change spend</th>
            </tr>
          </thead>
          <tbody>
            <BenchmarkRow benchmark={comparison.benchmark} />
            {comparison.rows.map((row) => (
              <Row
                key={row.scopeId}
                row={row}
                focused={row.scopeId === focusRowId}
                revealToken={revealToken}
              />
            ))}
          </tbody>
        </table>
      </div>

      {comparison.rows.length === 0 && (
        <p className="comparison__empty" role="status">
          No {comparison.grouping} had any eligible activity in this selection. The benchmark above
          still covers the organisation.
        </p>
      )}

      <details className="disclosure">
        <summary>About these comparisons</summary>
        <BenchmarkExplanation benchmark={comparison.benchmark} />
      </details>
    </section>
  )
}

/** Pinned first, and labelled as the organisation's own pooled figure — not a row total. */
function BenchmarkRow({ benchmark }: { readonly benchmark: Benchmark }) {
  return (
    <tr className="data-table__row data-table__row--benchmark">
      <th scope="row">
        <span className="comparison__scope">Your organisation</span>
        <span className="comparison__scope-note">
          Pooled across the organisation, including scopes not listed below. Not the sum of the
          visible rows.
        </span>
      </th>
      <td>
        <MetricValue metric={benchmark.taskCompletionRate} />
      </td>
      <td>
        <MetricValue metric={benchmark.terminalMergeRate} />
      </td>
      <td>
        <MetricValue metric={benchmark.costPerMergedPr} />
      </td>
      <td>
        <MetricValue metric={benchmark.codeChangeSpend} />
      </td>
    </tr>
  )
}

function Row({
  row,
  focused,
  revealToken,
}: {
  readonly row: ComparisonRow
  readonly focused: boolean
  readonly revealToken: string | null
}) {
  // Revealed only once the destination this row belongs to has actually rendered.
  const reveal = useReveal(focused ? revealToken : null)
  return (
    <tr
      ref={reveal}
      className={focused ? 'data-table__row data-table__row--focused' : 'data-table__row'}
      data-scope-id={row.scopeId}
      {...(focused ? { 'aria-current': 'true' as const } : {})}
    >
      <th scope="row">
        <span className="comparison__scope">{row.scopeName}</span>
        <span className="comparison__scope-note">{row.terminalTaskCount} finished tasks</span>
      </th>
      <td>
        <MetricValue metric={row.taskCompletionRate} />
        <ComparisonValue comparison={row.taskCompletionRate.comparison} basis="organisationBenchmark" />
      </td>
      <td>
        <MetricValue metric={row.terminalMergeRate} />
        <ComparisonValue comparison={row.terminalMergeRate.comparison} basis="organisationBenchmark" />
      </td>
      <td>
        <MetricValue metric={row.costPerMergedPr} />
        <ComparisonValue comparison={row.costPerMergedPr.comparison} basis="organisationBenchmark" />
      </td>
      <td>
        {/* Kept beside an undefined unit cost: spend of nothing and no merges are different facts. */}
        <MetricValue metric={row.codeChangeSpend} />
      </td>
    </tr>
  )
}

function BenchmarkExplanation({ benchmark }: { readonly benchmark: Benchmark }) {
  const scope = benchmark.scope
  return (
    <>
      <p>
        Each row is compared with the organisation benchmark, which pools counts and spend rather
        than averaging the rows’ percentages.
      </p>
      <ul className="comparison__scope-list">
        {scope.includesSelectedTeam && (
          <li>It includes the selected team’s own contribution — this is not “everyone else”.</li>
        )}
        {scope.teamFilterIgnored && <li>It ignores the team filter, by design.</li>}
        {scope.repositoryFilterApplied ? (
          <li>It honours the repository filter you have applied.</li>
        ) : (
          <li>No repository filter is applied, so it covers every repository.</li>
        )}
        {scope.mayIncludeUndisplayedTeams && (
          <li>Because a team filter is active, it may include teams that are not listed here.</li>
        )}
      </ul>
      <p>
        Selecting or focusing a row does not change the benchmark. A comparison is shown only when
        both the row and the benchmark have enough activity to support one; otherwise the cell says
        which threshold was not met.
      </p>
    </>
  )
}
