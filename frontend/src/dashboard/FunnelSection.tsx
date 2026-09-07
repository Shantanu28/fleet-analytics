/**
 * The cohort funnel: the tasks that *started* in the selected period, followed wherever they ended.
 *
 * Two things this section has to say out loud. The funnel and the KPI cards count different
 * populations and need not reconcile (AC-03.5), and a recent cohort has had less time to reach merge
 * — stated in words, with no numeric maturity threshold, because the contract defines none
 * (AC-03.4).
 *
 * Bars are scaled from the supplied counts. No stage percentage is shown: the API does not supply
 * one, and computing it here would add a reported metric rather than draw a supplied one.
 */
import type { Funnel, Metric, Selection } from '../api/dashboard'
import { hasDisplay } from '../api/dashboard'
import { MetricValue } from './MetricValue'
import { formatCount, formatUtcDate, formatUtcRange } from './presentation'

function countOf(metric: Metric): number | null {
  if (!hasDisplay(metric)) return null
  const parsed = Number(metric.display.value)
  return Number.isFinite(parsed) ? parsed : null
}

export function FunnelSection({
  funnel,
  selection,
}: {
  readonly funnel: Funnel
  readonly selection: Selection
}) {
  const stages = [
    { key: 'started', label: 'Tasks started', metric: funnel.stages.started },
    { key: 'completed', label: 'Completed', metric: funnel.stages.completed },
    { key: 'prOpened', label: 'PR opened', metric: funnel.stages.prOpened },
    { key: 'prMerged', label: 'PR merged', metric: funnel.stages.prMerged },
  ] as const

  // The widest bar is the largest count actually available, so a missing stage neither scales the
  // chart nor is drawn as a zero-width bar that would read as "none".
  const scale = Math.max(...stages.map((stage) => countOf(stage.metric) ?? 0), 1)

  const started = countOf(funnel.stages.started)
  const parts = [
    countOf(funnel.stages.completed),
    countOf(funnel.sideExits.failed),
    countOf(funnel.sideExits.cancelled),
    countOf(funnel.residual.inProgress),
  ]
  const identityAvailable = started !== null && parts.every((part) => part !== null)

  return (
    <section className="panel funnel" aria-labelledby="funnel-heading">
      <div className="panel__head">
        <h2 className="panel__title" id="funnel-heading">
          Outcome funnel — code-change tasks
        </h2>
        <p className="panel__subtitle">
          Tasks started {formatUtcRange(selection.from, selection.to)}. Outcomes followed through{' '}
          {formatUtcDate(funnel.observationCutoff)} (UTC).
        </p>
      </div>

      {/* The funnel's own emptiness, explained on its own terms: cohort membership is fixed by when
          a task *started*, a different basis from every card above (AC-07.3, contract §2, §4). */}
      {started === 0 && (
        <p className="funnel__zero-note" role="note">
          No eligible code-change tasks started during the selected period, so this cohort is empty.
          Tasks are placed here by when they started, so work that started earlier and finished in
          this period is not counted. Use Reset filters above to widen the selection.
        </p>
      )}

      <div className="funnel__layout">
        <ol className="funnel__stages">
          {stages.map((stage) => {
            const count = countOf(stage.metric)
            return (
              <li className="funnel__stage" key={stage.key}>
                <span className="funnel__stage-label">{stage.label}</span>
                <span className="funnel__bar-track" aria-hidden="true">
                  {count !== null && (
                    <span
                      className="funnel__bar"
                      style={{ width: `${Math.max((count / scale) * 100, count > 0 ? 1 : 0)}%` }}
                    />
                  )}
                </span>
                <span className="funnel__stage-value">
                  <MetricValue metric={stage.metric} />
                </span>
              </li>
            )
          })}
        </ol>

        <div className="funnel__exits">
          {/* Side exits ended; the residual has not. Kept apart so an in-progress task is never
              presented as an outcome (AC-03.2). */}
          <div className="funnel__exit-group">
            <h3 className="funnel__exit-title">Side exits — task ended</h3>
            <dl className="funnel__exit-list">
              <div className="funnel__exit-row">
                <dt>Failed</dt>
                <dd>
                  <MetricValue metric={funnel.sideExits.failed} />
                </dd>
              </div>
              <div className="funnel__exit-row">
                <dt>Cancelled</dt>
                <dd>
                  <MetricValue metric={funnel.sideExits.cancelled} />
                </dd>
              </div>
            </dl>
          </div>

          <div className="funnel__exit-group">
            <h3 className="funnel__exit-title">Still running</h3>
            <dl className="funnel__exit-list">
              <div className="funnel__exit-row">
                <dt>In progress</dt>
                <dd>
                  <MetricValue metric={funnel.residual.inProgress} />
                </dd>
              </div>
            </dl>
          </div>

          {identityAvailable && (
            <p className="funnel__identity">
              {formatCount(parts[0]!)} completed + {formatCount(parts[1]!)} failed +{' '}
              {formatCount(parts[2]!)} cancelled + {formatCount(parts[3]!)} in progress ={' '}
              {formatCount(started!)} started
            </p>
          )}
        </div>
      </div>

      <details className="disclosure">
        <summary>Why the funnel and the cards differ</summary>
        <p>
          The cards count what happened inside{' '}
          {formatUtcRange(selection.from, selection.to)}. The funnel takes the tasks that{' '}
          <em>started</em> in that window and follows them wherever they finished, up to{' '}
          {formatUtcDate(funnel.observationCutoff)} — so a PR merged after the range still counts
          here and not in the cards. The two are different populations and are not expected to
          reconcile.
        </p>
        <p>
          Tasks that started recently have had less time to reach a merge, so the later stages of a
          recent cohort are naturally thinner. Failed and cancelled tasks have ended; in-progress
          tasks have not, so they are shown as a residual rather than an exit.
        </p>
      </details>
    </section>
  )
}
