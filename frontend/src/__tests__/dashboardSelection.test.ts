import { describe, expect, it } from 'vitest'
import { lastCompleteUtcDate, presetRange, shiftUtcDate } from '../api/coverage'
import {
  applyFindingLink,
  matchingPreset,
  readSelection,
  resetSelection,
  toRequestSelection,
  toSearchString,
  withCustomRange,
  withFilter,
  withGrouping,
  withPreset,
  type Selection,
} from '../dashboard/selection'
import { REPO_API, TEAM_PAYMENTS, budgetFinding, failureFinding } from './dashboardFixtures'

/** The M4 dataset's fixed cutoff: the last complete day is 31 August 2026. */
const DATA_THROUGH = '2026-09-01T00:00:00Z'

describe('UTC date arithmetic', () => {
  it('reports the last complete day, not the exclusive cutoff', () => {
    expect(lastCompleteUtcDate(DATA_THROUGH)).toBe('2026-08-31')
  })

  /** Month, year and leap-year boundaries must fall out of the arithmetic, not need special cases. */
  it.each([
    ['2026-03-01T00:00:00Z', '2026-02-28'],
    ['2028-03-01T00:00:00Z', '2028-02-29'],
    ['2026-01-01T00:00:00Z', '2025-12-31'],
  ])('handles the boundary at %s', (dataThrough, expected) => {
    expect(lastCompleteUtcDate(dataThrough)).toBe(expected)
  })

  it('shifts across a year boundary', () => {
    expect(shiftUtcDate('2026-01-02', -7)).toBe('2025-12-26')
    expect(shiftUtcDate('2028-03-01', -1)).toBe('2028-02-29')
  })

  it('returns null rather than a fabricated date for an unparseable cutoff', () => {
    expect(lastCompleteUtcDate('not-a-date')).toBeNull()
    expect(presetRange('not-a-date', 30)).toBeNull()
  })

  /** Presets are inclusive and anchored to the dataset, never to the wall clock (AC-02.1). */
  it.each([
    [7, '2026-08-25'],
    [30, '2026-08-02'],
    [90, '2026-06-03'],
  ])('resolves the %s-day preset inclusively', (days, from) => {
    expect(presetRange(DATA_THROUGH, days)).toEqual({ from, to: '2026-08-31' })
  })

  it('resolves a preset that spans a month boundary', () => {
    expect(presetRange('2026-03-01T00:00:00Z', 7)).toEqual({ from: '2026-02-22', to: '2026-02-28' })
  })
})

describe('reading the URL', () => {
  it('keeps only the parameters the URL actually supplied', () => {
    expect(readSelection('?from=2026-08-02&to=2026-08-31')).toEqual({
      from: '2026-08-02',
      to: '2026-08-31',
    })
  })

  /**
   * A blank value is a supplied value. Treating it as absent would answer the 30-day default while
   * the user believes their input was used, and they would never be told otherwise.
   */
  it('distinguishes a blank supplied value from an omitted one', () => {
    expect(readSelection('?from=&to=2026-08-31')).toEqual({ from: '', to: '2026-08-31' })
    expect(readSelection('?to=2026-08-31')).toEqual({ to: '2026-08-31' })
  })

  it('preserves malformed dates and unknown ids for the server to reject', () => {
    const selection = readSelection('?from=31-08-2026&to=2026-08-31&teamId=not-a-uuid')

    expect(selection.from).toBe('31-08-2026')
    expect(selection.teamId).toBe('not-a-uuid')
  })

  it('ignores parameters the dashboard does not accept', () => {
    expect(readSelection('?from=2026-08-02&utm_source=email')).toEqual({ from: '2026-08-02' })
  })

  it('writes parameters in one canonical order, so equivalent selections share a query key', () => {
    const a: Selection = { grouping: 'teams', from: '2026-08-02', teamId: TEAM_PAYMENTS }
    const b: Selection = { teamId: TEAM_PAYMENTS, from: '2026-08-02', grouping: 'teams' }

    expect(toSearchString(a)).toBe(toSearchString(b))
    expect(toSearchString(a)).toBe(`?from=2026-08-02&teamId=${TEAM_PAYMENTS}&grouping=teams`)
  })
})

describe('the requested selection', () => {
  it('defaults to the last 30 complete days when no dates are supplied', () => {
    expect(toRequestSelection({}, DATA_THROUGH)).toEqual({
      from: '2026-08-02',
      to: '2026-08-31',
      grouping: 'teams',
    })
  })

  /**
   * A half-supplied range is forwarded untouched. Inventing the missing half would turn a mistake
   * the server would have named into a different, silently answered question.
   */
  it('forwards a half-supplied range rather than completing it', () => {
    expect(toRequestSelection({ from: '2026-08-02' }, DATA_THROUGH)).toEqual({
      from: '2026-08-02',
      grouping: 'teams',
    })
    expect(toRequestSelection({ to: '2026-08-31' }, DATA_THROUGH)).toEqual({
      to: '2026-08-31',
      grouping: 'teams',
    })
  })

  it('forwards blank and malformed dates unchanged', () => {
    expect(toRequestSelection({ from: '', to: '' }, DATA_THROUGH)).toEqual({
      from: '',
      to: '',
      grouping: 'teams',
    })
  })

  it('omits dates entirely when coverage is not yet known', () => {
    expect(toRequestSelection({}, null)).toEqual({ grouping: 'teams' })
  })

  it('keeps both filters so results reflect their intersection', () => {
    const request = toRequestSelection(
      { teamId: TEAM_PAYMENTS, repositoryId: REPO_API },
      DATA_THROUGH,
    )

    expect(request.teamId).toBe(TEAM_PAYMENTS)
    expect(request.repositoryId).toBe(REPO_API)
  })
})

describe('applied changes', () => {
  const applied: Selection = {
    from: '2026-08-02',
    to: '2026-08-31',
    teamId: TEAM_PAYMENTS,
    repositoryId: REPO_API,
    grouping: 'teams',
  }

  it('recognises which preset an applied range corresponds to', () => {
    expect(matchingPreset({ from: '2026-08-25', to: '2026-08-31' }, DATA_THROUGH)).toBe(7)
    expect(matchingPreset(applied, DATA_THROUGH)).toBe(30)
    expect(matchingPreset({ from: '2026-08-01', to: '2026-08-31' }, DATA_THROUGH)).toBeNull()
  })

  it('changes only the dates for a preset, keeping filters and grouping', () => {
    const next = withPreset(applied, 7, DATA_THROUGH)

    expect(next).toEqual({ ...applied, from: '2026-08-25', to: '2026-08-31' })
  })

  /** A range is one decision: both dates move together or neither does (AC-02.2). */
  it('applies a custom range atomically', () => {
    const next = withCustomRange(applied, '2026-05-01', '2026-08-31')

    expect(next.from).toBe('2026-05-01')
    expect(next.to).toBe('2026-08-31')
    expect(next.teamId).toBe(TEAM_PAYMENTS)
  })

  it('accepts a custom range longer than any preset', () => {
    const next = withCustomRange(applied, '2026-03-05', '2026-08-31')

    expect(toRequestSelection(next, DATA_THROUGH).from).toBe('2026-03-05')
  })

  it('clears a filter by removing its parameter, not by blanking it', () => {
    const next = withFilter(applied, 'repositoryId', null)

    expect(Object.hasOwn(next, 'repositoryId')).toBe(false)
    expect(toSearchString(next)).not.toContain('repositoryId')
  })

  /** Switching the table view must not disturb the dates or either filter (AC-05.1, AC-05.7). */
  it('changes only the grouping', () => {
    const next = withGrouping(applied, 'repositories')

    expect(next).toEqual({ ...applied, grouping: 'repositories' })
  })

  it('resets to the default range with no filters', () => {
    expect(resetSelection(DATA_THROUGH)).toEqual({
      from: '2026-08-02',
      to: '2026-08-31',
      grouping: 'teams',
    })
  })
})

describe('finding-link patches', () => {
  const origin: Selection = {
    from: '2026-08-02',
    to: '2026-08-31',
    teamId: TEAM_PAYMENTS,
    repositoryId: REPO_API,
    grouping: 'teams',
  }

  /**
   * The case a truthiness check would break: the link's explicit `repositoryId: null` must clear the
   * filter. Preserved instead, the destination would be a repository scope where budgets are not
   * evaluated, so the finding could not be reproduced there.
   */
  it('clears a filter for an explicit null and sets the dates it supplies', () => {
    const next = applyFindingLink(origin, budgetFinding.link)

    expect(Object.hasOwn(next, 'repositoryId')).toBe(false)
    expect(next.teamId).toBe(TEAM_PAYMENTS)
    expect(next.from).toBe('2026-08-01')
    expect(next.to).toBe('2026-08-14')
    expect(next.grouping).toBe('teams')
  })

  it('clears both filters for an organisation-scope budget finding', () => {
    const next = applyFindingLink(origin, {
      section: 'spendTrend',
      from: '2026-08-01',
      to: '2026-08-14',
      teamId: null,
      repositoryId: null,
    })

    expect(Object.hasOwn(next, 'teamId')).toBe(false)
    expect(Object.hasOwn(next, 'repositoryId')).toBe(false)
  })

  /** Absent means preserve: a repository finding says nothing about the team, so Payments stays. */
  it('preserves omitted dates and the other dimension', () => {
    const next = applyFindingLink(origin, failureFinding.link)

    expect(next.teamId).toBe(TEAM_PAYMENTS)
    expect(next.from).toBe('2026-08-02')
    expect(next.to).toBe('2026-08-31')
    expect(next.repositoryId).toBe(REPO_API)
    expect(next.grouping).toBe('repositories')
  })

  it('never copies undocumented link fields into the selection', () => {
    const next = applyFindingLink(origin, {
      ...failureFinding.link,
      ...({ focusRowId: REPO_API, section: 'comparisonTable', somethingNew: 'x' } as object),
    })

    expect(toSearchString(next)).not.toContain('focusRowId')
    expect(toSearchString(next)).not.toContain('section')
    expect(toSearchString(next)).not.toContain('somethingNew')
  })
})
