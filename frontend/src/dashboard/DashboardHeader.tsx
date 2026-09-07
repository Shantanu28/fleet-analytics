/**
 * Identity, demo framing and the dates the figures on screen actually describe.
 *
 * The reporting cutoff comes from the response being displayed, not from a clock or from the
 * context request, so the stated cutoff always belongs to the numbers beside it. Before any of it
 * has loaded the header says so rather than printing a plausible date it cannot yet justify
 * (AC-01.3).
 */
import type { Selection } from '../api/dashboard'
import type { Role } from '../api/client'
import { formatUtcDate, formatUtcRange } from './presentation'

export function DashboardHeader({
  organisationName,
  displayName,
  role,
  selection,
  observationCutoff,
  onSignOut,
}: {
  readonly organisationName: string | null
  readonly displayName: string
  readonly role: Role
  readonly selection: Selection | null
  readonly observationCutoff: string | null
  readonly onSignOut: () => void
}) {
  return (
    <header className="page-header">
      <div className="page-header__identity">
        <h1 className="page-header__title">
          <span className="page-header__product">Fleet Analytics</span>
          <span className="page-header__scope">
            {organisationName === null ? 'Loading organisation…' : organisationName}
          </span>
        </h1>
        <p className="page-header__meta">
          <span className="badge badge--demo">Synthetic demo data</span>{' '}
          <span className="page-header__cutoff">
            {observationCutoff === null
              ? 'Reporting cutoff not yet known'
              : `Complete through ${formatUtcDate(observationCutoff)} (UTC)`}
          </span>
        </p>
      </div>

      <div className="page-header__account">
        <p className="page-header__viewer">
          Viewing as {displayName} <span className="page-header__role">({role})</span>
        </p>
        {/* Always available: signing out must never wait for a request to finish or succeed. */}
        <button type="button" className="button button--quiet" onClick={onSignOut}>
          Sign out
        </button>
      </div>

      {selection !== null && (
        <p className="page-header__selection">
          Showing <strong>{formatUtcRange(selection.from, selection.to)}</strong>
          {' · compared with '}
          <strong>{formatUtcRange(selection.previousFrom, selection.previousTo)}</strong>
        </p>
      )}
    </header>
  )
}
