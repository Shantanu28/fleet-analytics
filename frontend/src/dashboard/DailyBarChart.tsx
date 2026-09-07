/**
 * One daily series as a responsive SVG bar chart, with a complete table equivalent.
 *
 * The SVG is presentation. It has `aria-hidden`, and the table beside it carries every plotted
 * point — a chart nobody can read is not an accessible chart, and the table is the equivalent, not a
 * summary (AC-04.3, AC-08.5).
 *
 * The chart draws only what it is given: no summing, no rebucketing, no total. `viewBox` with
 * `preserveAspectRatio="none"` lets it shrink with its container without overflowing the page.
 */
import { useId } from 'react'
import { formatUtcDate } from './presentation'

export type ChartPoint = { readonly date: string; readonly amount: number }

const VIEW_WIDTH = 600
const VIEW_HEIGHT = 160

export function DailyBarChart({
  title,
  unitLabel,
  points,
  formatAmount,
  axisLabel,
}: {
  readonly title: string
  readonly unitLabel: string
  readonly points: readonly ChartPoint[]
  /** Turns an exact server value into an axis or cell label. Presentation, never a calculation. */
  readonly formatAmount: (amount: number) => string
  readonly axisLabel: string
}) {
  const tableId = useId()
  const peak = Math.max(...points.map((point) => point.amount), 0)
  // An all-zero series still needs a usable axis: a zero denominator would make every bar NaN.
  const scale = peak > 0 ? peak : 1
  const slot = VIEW_WIDTH / Math.max(points.length, 1)

  return (
    <figure className="chart">
      <figcaption className="chart__caption">
        <span className="chart__title">{title}</span>
        <span className="chart__unit">{unitLabel}</span>
      </figcaption>

      {points.length === 0 ? (
        <p className="chart__empty">No days to plot for this selection.</p>
      ) : (
        <>
          <div className="chart__plot">
            <ul className="chart__axis" aria-hidden="true">
              <li>{formatAmount(peak)}</li>
              <li>{formatAmount(0)}</li>
            </ul>
            <svg
              className="chart__svg"
              viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
              preserveAspectRatio="none"
              role="presentation"
              aria-hidden="true"
              focusable="false"
            >
              <line
                x1="0"
                y1={VIEW_HEIGHT}
                x2={VIEW_WIDTH}
                y2={VIEW_HEIGHT}
                className="chart__baseline"
              />
              {points.map((point, index) => {
                const height = (point.amount / scale) * (VIEW_HEIGHT - 4)
                return (
                  <rect
                    key={point.date}
                    className="chart__bar"
                    x={index * slot + slot * 0.15}
                    width={Math.max(slot * 0.7, 0.5)}
                    // A zero day is drawn as a baseline tick, so it reads as a plotted zero
                    // rather than as a gap in the data (AC-04.2).
                    y={VIEW_HEIGHT - Math.max(height, point.amount > 0 ? 1 : 0.75)}
                    height={Math.max(height, point.amount > 0 ? 1 : 0.75)}
                  />
                )
              })}
            </svg>
          </div>
          <p className="chart__range" aria-hidden="true">
            <span>{formatUtcDate(points[0]!.date)}</span>
            <span>{formatUtcDate(points[points.length - 1]!.date)}</span>
          </p>

          <details className="disclosure chart__data">
            <summary>View data — {title.toLowerCase()}, day by day</summary>
            <div className="table-scroll" tabIndex={0} role="region" aria-labelledby={tableId}>
              <table className="data-table">
                <caption id={tableId}>
                  {title} — {unitLabel}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Date (UTC)</th>
                    <th scope="col">{axisLabel}</th>
                  </tr>
                </thead>
                <tbody>
                  {points.map((point) => (
                    <tr key={point.date}>
                      <th scope="row">{point.date}</th>
                      <td>{formatAmount(point.amount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      )}
    </figure>
  )
}
