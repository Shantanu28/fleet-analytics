/**
 * The applied selection lives in the URL, and this module is the only place that reads or writes it.
 *
 * Parameters are kept **verbatim**. A blank `from=`, a malformed date and an unknown team id all
 * survive into the request unchanged, because the server distinguishes them and answers each
 * differently. Repairing them here would substitute a valid unfiltered request for the one the user
 * actually asked for, and they would never learn their input was discarded (AC-02.7, AC-02.8).
 */
import { presetRange } from '../api/coverage'
import type { FindingLink, Grouping } from '../api/dashboard'

/** Every parameter the dashboard endpoint accepts, in the order URLs are written in. */
export const SELECTION_PARAMETERS = ['from', 'to', 'teamId', 'repositoryId', 'grouping'] as const
export type SelectionParameter = (typeof SELECTION_PARAMETERS)[number]

export const DEFAULT_RANGE_DAYS = 30
export const PRESET_DAYS = [7, 30, 90] as const
export type PresetDays = (typeof PRESET_DAYS)[number]

/** Present keys only. An absent key means the URL said nothing, which is not the same as blank. */
export type Selection = Partial<Record<SelectionParameter, string>>

export function readSelection(search: string): Selection {
  const parameters = new URLSearchParams(search)
  const selection: Selection = {}
  for (const name of SELECTION_PARAMETERS) {
    const value = parameters.get(name)
    if (value !== null) selection[name] = value
  }
  return selection
}

/** Canonical order, so two equivalent selections produce one URL and therefore one query key. */
export function toSearchParams(selection: Selection): URLSearchParams {
  const parameters = new URLSearchParams()
  for (const name of SELECTION_PARAMETERS) {
    const value = selection[name]
    if (value !== undefined) parameters.set(name, value)
  }
  return parameters
}

export function toSearchString(selection: Selection): string {
  const query = toSearchParams(selection).toString()
  return query ? `?${query}` : ''
}

/**
 * The selection actually sent to the server.
 *
 * Dates are defaulted from published coverage only when **both** are absent — a half-supplied range
 * is forwarded as-is so the server can report it as incomplete rather than having the missing half
 * invented here. Grouping defaults to Teams, matching the server.
 */
export function toRequestSelection(selection: Selection, dataThrough: string | null): Selection {
  const request: Selection = { ...selection }
  if (request.from === undefined && request.to === undefined && dataThrough !== null) {
    const range = presetRange(dataThrough, DEFAULT_RANGE_DAYS)
    if (range !== null) {
      request.from = range.from
      request.to = range.to
    }
  }
  if (request.grouping === undefined) request.grouping = 'teams'
  return request
}

/** True when the URL already says exactly what would be requested, so canonicalising is a no-op. */
export function isCanonical(selection: Selection, request: Selection): boolean {
  return toSearchString(selection) === toSearchString(request)
}

export function withPreset(selection: Selection, days: number, dataThrough: string): Selection {
  const range = presetRange(dataThrough, days)
  if (range === null) return selection
  return { ...selection, from: range.from, to: range.to }
}

/** Which preset the applied range corresponds to, or null for a custom range. */
export function matchingPreset(selection: Selection, dataThrough: string | null): PresetDays | null {
  if (dataThrough === null) return null
  for (const days of PRESET_DAYS) {
    const range = presetRange(dataThrough, days)
    if (range !== null && selection.from === range.from && selection.to === range.to) return days
  }
  return null
}

/** Both dates move together: a range is one decision, not two independent ones (AC-02.2). */
export function withCustomRange(selection: Selection, from: string, to: string): Selection {
  return { ...selection, from, to }
}

export function withFilter(
  selection: Selection,
  name: 'teamId' | 'repositoryId',
  id: string | null,
): Selection {
  const next: Selection = { ...selection }
  if (id === null) delete next[name]
  else next[name] = id
  return next
}

/** Switching the table view changes the grouping and nothing else (AC-05.1). */
export function withGrouping(selection: Selection, grouping: Grouping): Selection {
  return { ...selection, grouping }
}

/** Reset: default range, all teams, all repositories, Teams grouping (AC-02.9). */
export function resetSelection(dataThrough: string | null): Selection {
  const range = dataThrough === null ? null : presetRange(dataThrough, DEFAULT_RANGE_DAYS)
  return {
    ...(range === null ? {} : { from: range.from, to: range.to }),
    grouping: 'teams',
  }
}

/**
 * Applies a finding's navigation link as a tri-state patch (AC-06.4 to AC-06.7):
 *
 * - a key **absent** from the link means preserve what the user already has;
 * - a key present with **null** means clear that filter;
 * - a key with a value means set it.
 *
 * `Object.hasOwn` is what distinguishes the first two. A truthiness or `!== undefined` check would
 * treat "clear" as "preserve", so a budget link would silently stop clearing the repository filter
 * and land the user in a scope where budgets are not evaluated at all — a broken destination that
 * still looks like working navigation.
 *
 * Only the documented selection fields are read. Nothing is spread from the link, so a field added
 * to the API later cannot become an unvalidated request parameter.
 */
export function applyFindingLink(selection: Selection, link: FindingLink): Selection {
  const next: Selection = { ...selection }

  for (const name of ['teamId', 'repositoryId'] as const) {
    if (!Object.hasOwn(link, name)) continue
    const value = link[name]
    if (value === null) delete next[name]
    else if (value !== undefined) next[name] = value
  }

  for (const name of ['from', 'to', 'grouping'] as const) {
    if (!Object.hasOwn(link, name)) continue
    const value = link[name]
    if (typeof value === 'string') next[name] = value
  }

  return next
}
