/**
 * One metric, rendered so that zero, unavailable and suppressed are visibly different things.
 *
 * A value and its absence must never look alike. A defined `0.0%` renders as a real figure; an
 * unavailable metric renders the server's own explanation in its place, in a distinct style and with
 * a text marker so the difference survives without colour (AC-01.6, AC-08.6).
 */
import type { Comparison, Metric, SeatsMetric } from '../api/dashboard'
import { comparisonHasDisplay, hasDisplay } from '../api/dashboard'
import {
  comparisonBasisLabel,
  comparisonGlyph,
  comparisonWord,
  formatDisplay,
  formatMetricValue,
  type ComparisonBasis,
} from './presentation'

export function MetricValue({
  metric,
  size = 'inline',
}: {
  readonly metric: Metric | SeatsMetric
  readonly size?: 'headline' | 'inline'
}) {
  const value = formatMetricValue(metric)
  if (value === null || !hasDisplay(metric)) {
    return <Unavailable reason={'reason' in metric ? metric.reason : undefined} />
  }
  return (
    <span className={size === 'headline' ? 'metric-value metric-value--headline' : 'metric-value'}>
      {value}
    </span>
  )
}

/**
 * The server's controlled sentence, never a generic placeholder. It is what tells a reader whether
 * a number does not exist, is not defined for this scope, or could not be read at all.
 */
export function Unavailable({ reason }: { readonly reason?: string }) {
  return (
    <span className="metric-unavailable">
      <span aria-hidden="true" className="metric-unavailable__mark">
        —
      </span>{' '}
      <span className="metric-unavailable__reason">{reason ?? 'Not available.'}</span>
    </span>
  )
}

/**
 * The one comparison this metric is allowed to show.
 *
 * When it is suppressed the card explains why in the delta's place — it never falls back to a
 * different comparison kind, and never adds a second badge (AC-01.4, AC-01.8).
 *
 * @param basis what the delta is measured against. Supplied by the caller because it depends on the
 *   rendering context, not on the comparison's units: the same `percentagePoints` kind means "vs the
 *   previous period" on a card and "vs the organisation benchmark" in a table row.
 */
export function ComparisonValue({
  comparison,
  basis,
}: {
  readonly comparison?: Comparison
  readonly basis: ComparisonBasis
}) {
  if (comparison === undefined) return null
  if (!comparisonHasDisplay(comparison)) {
    return (
      <p className="comparison comparison--suppressed">
        <span className="comparison__reason">{comparison.reason}</span>
      </p>
    )
  }
  return (
    <p className="comparison">
      <span aria-hidden="true" className="comparison__glyph">
        {comparisonGlyph(comparison.display)}
      </span>{' '}
      <strong className="comparison__delta">{formatDisplay(comparison.display, true)}</strong>{' '}
      {/* The direction is stated in words too, so the glyph is never the only carrier (AC-08.6). */}
      <span className="comparison__sr">{comparisonWord(comparison.display)}</span>
      <span className="comparison__basis">{comparisonBasisLabel(basis)}</span>
    </p>
  )
}
