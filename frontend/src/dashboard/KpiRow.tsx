/**
 * The five headline cards, in the order `00-research.md` §7 freezes.
 *
 * Each card shows one value, one comparison and one definition — no second delta, no sixth card
 * (AC-01.1). The comparison kind per card is fixed by AC-01.7 and comes from the server, so the
 * card never chooses or substitutes one.
 */
import type { Kpis, Metric } from '../api/dashboard'
import { hasDisplay } from '../api/dashboard'
import { ComparisonValue, MetricValue, Unavailable } from './MetricValue'
import { formatDisplay, isZeroDisplay } from './presentation'

/** A computed zero, as distinct from an unavailable metric that has no value at all. */
function isDefinedZero(metric: Metric): boolean {
  return hasDisplay(metric) && isZeroDisplay(metric.display)
}

export function KpiRow({ kpis }: { readonly kpis: Kpis }) {
  const seats = kpis.seats
  return (
    <section className="kpis" aria-label="Headline measures">
      <KpiCard
        label="Merged agent PRs"
        definition="Agent PRs merged into the default branch. A sign of work being accepted, not a quality score."
      >
        <MetricValue metric={kpis.mergedPrs} size="headline" />
        {/* A defined zero is a real answer, and it gets its own explanation naming this metric's
            own population and timestamp basis — merges are placed by when the PR merged, so this
            says nothing about any other section (AC-07.3, contract §2). */}
        {isDefinedZero(kpis.mergedPrs) && (
          <p className="kpi-card__zero-note" role="note">
            No eligible agent PRs merged during the selected period. Merges are counted on the day
            the PR merged, so a PR opened in this period but merged later is not counted here. Use
            Reset filters above to widen the selection.
          </p>
        )}
        <ComparisonValue comparison={kpis.mergedPrs.comparison} basis="previousPeriod" />
      </KpiCard>

      <KpiCard
        label="Terminal PR merge rate"
        definition="Share of finished agent PRs that were merged. PRs still open are not counted."
      >
        <MetricValue metric={kpis.terminalMergeRate} size="headline" />
        <ComparisonValue comparison={kpis.terminalMergeRate.comparison} basis="previousPeriod" />
      </KpiCard>

      <KpiCard
        label="Blended cost per merged PR"
        definition="Code-change spend divided by merged PRs. A unit cost, not a return on investment."
      >
        <MetricValue metric={kpis.costPerMergedPr} size="headline" />
        <ComparisonValue comparison={kpis.costPerMergedPr.comparison} basis="previousPeriod" />
      </KpiCard>

      <KpiCard
        label="Task completion rate"
        definition="Completed out of completed plus failed code-change tasks. Cancelled and still-running tasks are left out."
      >
        <MetricValue metric={kpis.taskCompletionRate} size="headline" />
        <ComparisonValue comparison={kpis.taskCompletionRate.comparison} basis="previousPeriod" />
      </KpiCard>

      <KpiCard
        label="Active seats / licensed seats"
        definition="People who ran at least one task, against the seats you licence."
      >
        {/* Explicit spaces, not just a CSS gap: the gap is invisible to a screen reader, which
            would otherwise hear "two slash six" as a single run-together token. */}
        <p className="kpi-card__seats">
          <MetricValue metric={seats} size="headline" />{' '}
          <span className="kpi-card__seats-divider" aria-hidden="true">
            /
          </span>{' '}
          <span className="metric-value metric-value--headline">{seats.licensedSeats}</span>{' '}
          <span className="kpi-card__seats-caption">licensed</span>
        </p>
        <ComparisonValue comparison={seats.comparison} basis="previousPeriod" />
        {/* Utilisation is stated separately and carries no comparison of its own (AC-01.8). Under a
            filter it is unavailable, because no team or repository seat allocation exists. */}
        <p className="kpi-card__utilisation">
          <span className="kpi-card__utilisation-label">Utilisation</span>{' '}
          {hasDisplay(seats.utilisation) ? (
            <strong>{formatDisplay(seats.utilisation.display)}</strong>
          ) : (
            <Unavailable
              reason={'reason' in seats.utilisation ? seats.utilisation.reason : undefined}
            />
          )}
        </p>
      </KpiCard>
    </section>
  )
}

function KpiCard({
  label,
  definition,
  children,
}: {
  readonly label: string
  readonly definition: string
  readonly children: React.ReactNode
}) {
  return (
    <article className="kpi-card">
      <h3 className="kpi-card__label">{label}</h3>
      <div className="kpi-card__body">{children}</div>
      <p className="kpi-card__definition">{definition}</p>
    </article>
  )
}
