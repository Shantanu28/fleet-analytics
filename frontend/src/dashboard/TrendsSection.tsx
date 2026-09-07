/**
 * Two independent daily charts — merged PRs and spend — each with its own axis and unit.
 *
 * Never a dual axis: PRs and dollars share no scale, and overlaying them would invite a comparison
 * neither series supports (AC-04.1). The two populations differ too, and each says so: merged PRs
 * count code-change tasks only, while spend covers every task type (AC-04.4).
 *
 * An unavailable series renders its reason and *no* points. A run of zeros would assert that nothing
 * happened, which is a different and false claim (contract §5.1).
 */
import type { Selection, Trends } from '../api/dashboard'
import { DailyBarChart } from './DailyBarChart'
import { useReveal } from './DashboardPage'
import { centsToDollarLabel, formatCount, formatUtcRange } from './presentation'

export function TrendsSection({
  trends,
  selection,
  revealToken,
}: {
  readonly trends: Trends
  readonly selection: Selection
  /** Non-null when a followed budget finding's destination is this section (AC-06.4). */
  readonly revealToken: string | null
}) {
  const reveal = useReveal(revealToken, true)
  return (
    <section
      ref={reveal}
      // Focusable only as a scroll and focus target, so a keyboard user arrives here rather than
      // being left at the link they activated. It is not in the tab order.
      tabIndex={-1}
      className={revealToken === null ? 'panel trends' : 'panel trends panel--highlighted'}
      aria-labelledby="trends-heading"
      id="spend-trend"
    >
      <div className="panel__head">
        <h2 className="panel__title" id="trends-heading">
          Daily trends
        </h2>
        <p className="panel__subtitle">
          {formatUtcRange(selection.from, selection.to)} · one bar per complete UTC day.
        </p>
      </div>

      <div className="trends__grid">
        <div className="trends__column">
          {trends.mergedPrsPerDay.state === 'ok' ? (
            <DailyBarChart
              title="Merged agent PRs per day"
              unitLabel="PRs / day · code-change tasks only"
              axisLabel="Merged PRs"
              points={trends.mergedPrsPerDay.points.map((point) => ({
                date: point.date,
                amount: point.value,
              }))}
              formatAmount={formatCount}
            />
          ) : (
            <UnavailableSeries
              title="Merged agent PRs per day"
              reason={trends.mergedPrsPerDay.reason}
            />
          )}
          <p className="chart__footnote">A day with no merges is shown as zero, not as a gap.</p>
        </div>

        <div className="trends__column">
          {trends.spendPerDay.state === 'ok' ? (
            <DailyBarChart
              title="Total agent spend per day"
              unitLabel="USD / day · all task types"
              axisLabel="Spend (USD)"
              points={trends.spendPerDay.points.map((point) => ({
                date: point.date,
                amount: point.spendCents,
              }))}
              formatAmount={centsToDollarLabel}
            />
          ) : (
            <UnavailableSeries title="Total agent spend per day" reason={trends.spendPerDay.reason} />
          )}
          <p className="chart__footnote">
            Includes every kind of task, so repository questions and research are counted here even
            though they never produce a PR.
          </p>
        </div>
      </div>
    </section>
  )
}

/** No points at all: unknown is not zero, and a flat line would claim otherwise. */
function UnavailableSeries({
  title,
  reason,
}: {
  readonly title: string
  readonly reason: string
}) {
  return (
    <figure className="chart chart--unavailable">
      <figcaption className="chart__caption">
        <span className="chart__title">{title}</span>
      </figcaption>
      <p className="chart__unavailable" role="status">
        <span aria-hidden="true">— </span>
        {reason}
      </p>
      <p className="chart__footnote">
        No days are plotted, because this series is unknown rather than zero.
      </p>
    </figure>
  )
}
