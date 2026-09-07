/**
 * The applied selection, and the controls that change it.
 *
 * The custom date inputs are **drafts**: local state until Apply. Editing one date of a range would
 * otherwise request a half-edited period and move the "Showing" label to something the user never
 * asked for — so the applied selection changes once, atomically (AC-02.2).
 *
 * Everything else applies immediately, because each is a single complete decision.
 */
import { useId, useState } from 'react'
import type { NamedEntity } from '../api/client'
import type { Grouping } from '../api/dashboard'
import { PRESET_DAYS, matchingPreset, type Selection } from './selection'

export function FilterBar({
  selection,
  teams,
  repositories,
  dataThrough,
  onPreset,
  onCustomRange,
  onFilter,
  onReset,
  disabled,
}: {
  readonly selection: Selection
  readonly teams: readonly NamedEntity[]
  readonly repositories: readonly NamedEntity[]
  readonly dataThrough: string | null
  readonly onPreset: (days: number) => void
  readonly onCustomRange: (from: string, to: string) => void
  readonly onFilter: (name: 'teamId' | 'repositoryId', id: string | null) => void
  readonly onReset: () => void
  readonly disabled: boolean
}) {
  const ids = {
    from: useId(),
    to: useId(),
    team: useId(),
    repository: useId(),
  }
  const activePreset = matchingPreset(selection, dataThrough)

  // Drafts start from the applied selection and resynchronise when it changes underneath them —
  // a Back navigation or a finding link must not leave stale text in the inputs.
  const applied = { from: selection.from ?? '', to: selection.to ?? '' }
  const [draft, setDraft] = useState(applied)
  const [appliedKey, setAppliedKey] = useState(`${applied.from}|${applied.to}`)
  const currentKey = `${applied.from}|${applied.to}`
  if (appliedKey !== currentKey) {
    setAppliedKey(currentKey)
    setDraft(applied)
  }

  const dirty = draft.from !== applied.from || draft.to !== applied.to

  return (
    <section className="filters" aria-label="Filters">
      <fieldset className="filters__group">
        <legend className="filters__legend">Date range</legend>
        <div className="filters__presets">
          {PRESET_DAYS.map((days) => (
            <button
              key={days}
              type="button"
              className="button button--preset"
              aria-pressed={activePreset === days}
              disabled={disabled || dataThrough === null}
              onClick={() => onPreset(days)}
            >
              {days} days
            </button>
          ))}
          <span className="filters__custom-state">
            {activePreset === null ? 'Custom range applied' : ''}
          </span>
        </div>
      </fieldset>

      <fieldset className="filters__group">
        <legend className="filters__legend">Custom range (UTC, inclusive)</legend>
        <div className="filters__range">
          <label className="filters__label" htmlFor={ids.from}>
            From
          </label>
          <input
            id={ids.from}
            type="date"
            className="input"
            value={draft.from}
            onChange={(event) => setDraft({ ...draft, from: event.target.value })}
          />
          <label className="filters__label" htmlFor={ids.to}>
            To
          </label>
          <input
            id={ids.to}
            type="date"
            className="input"
            value={draft.to}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
          />
          <button
            type="button"
            className="button button--primary"
            disabled={disabled || !dirty}
            onClick={() => onCustomRange(draft.from, draft.to)}
          >
            Apply
          </button>
        </div>
      </fieldset>

      <div className="filters__group">
        <label className="filters__legend" htmlFor={ids.team}>
          Team
        </label>
        <select
          id={ids.team}
          className="select"
          value={selection.teamId ?? ''}
          disabled={disabled}
          onChange={(event) => onFilter('teamId', event.target.value || null)}
        >
          <option value="">All teams</option>
          {teams.map((team) => (
            <option key={team.id} value={team.id}>
              {team.name}
            </option>
          ))}
          {/* An id from the URL that this organisation does not have still shows, so the control
              never silently disagrees with the request that was actually sent (AC-02.8). */}
          {selection.teamId !== undefined &&
            !teams.some((team) => team.id === selection.teamId) && (
              <option value={selection.teamId}>Unknown team</option>
            )}
        </select>
      </div>

      <div className="filters__group">
        <label className="filters__legend" htmlFor={ids.repository}>
          Repository
        </label>
        <select
          id={ids.repository}
          className="select"
          value={selection.repositoryId ?? ''}
          disabled={disabled}
          onChange={(event) => onFilter('repositoryId', event.target.value || null)}
        >
          <option value="">All repositories</option>
          {repositories.map((repository) => (
            <option key={repository.id} value={repository.id}>
              {repository.name}
            </option>
          ))}
          {selection.repositoryId !== undefined &&
            !repositories.some((repository) => repository.id === selection.repositoryId) && (
              <option value={selection.repositoryId}>Unknown repository</option>
            )}
        </select>
      </div>

      <div className="filters__group filters__group--reset">
        <button type="button" className="button" onClick={onReset}>
          Reset filters
        </button>
      </div>
    </section>
  )
}

/** The applied scope in words, so the labels and the URL always describe the same population. */
export function SelectionSummary({
  teamName,
  repositoryName,
  grouping,
}: {
  readonly teamName: string | null
  readonly repositoryName: string | null
  readonly grouping: Grouping
}) {
  return (
    <p className="selection-summary">
      {teamName === null ? 'All teams' : `Team: ${teamName}`}
      {' · '}
      {repositoryName === null ? 'All repositories' : `Repository: ${repositoryName}`}
      {' · '}
      {grouping === 'teams' ? 'Grouped by teams' : 'Grouped by repositories'}
    </p>
  )
}
