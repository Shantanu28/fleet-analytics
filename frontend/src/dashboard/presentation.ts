/**
 * Display formatting only.
 *
 * Every number here arrives from the server already rounded to its presentation precision, and this
 * module never changes that precision — it groups digits, attaches a unit and picks a sign. The
 * scale carries meaning: `"23"` is a whole-dollar spend total and `"23.00"` is a unit cost, so
 * reformatting either as a number would lose the distinction the contract went to some trouble to
 * make (contract §8.6).
 *
 * Nothing here divides, sums or compares a value to a threshold.
 */
import type { Display, Metric, SeatsMetric } from '../api/dashboard'
import { hasDisplay } from '../api/dashboard'

/**
 * Thousands separators on the integer part only, so a fractional value keeps every digit the server
 * sent. `Number(...).toLocaleString()` would reformat `"23.00"` to `"23"` and silently drop the
 * scale that distinguishes a unit cost from a whole-dollar total.
 */
function groupDigits(magnitude: string): string {
  const [whole, fraction] = magnitude.split('.')
  const grouped = (whole ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? grouped : `${grouped}.${fraction}`
}

const UNIT_SUFFIX: Record<Display['unit'], string> = {
  count: '',
  percent: '%',
  percentagePoints: ' pp',
  usd: '',
}

/** @param signed prefixes a non-negative value with `+`, for a comparison rather than a value. */
export function formatDisplay(display: Display, signed = false): string {
  const negative = display.value.startsWith('-')
  const magnitude = groupDigits(negative ? display.value.slice(1) : display.value)
  const sign = negative ? '-' : signed ? '+' : ''
  // The sign goes outside the currency symbol: "-$3.10", never "$-3.10".
  const body = display.unit === 'usd' ? `$${magnitude}` : magnitude
  return `${sign}${body}${UNIT_SUFFIX[display.unit]}`
}

/**
 * A spend total that rounds away to zero reads as "<$1" (contract §8.6). Only spend totals carry
 * the flag, and only a genuinely positive amount sets it — a real zero must still read as `$0`,
 * or true inactivity would look like a rounding artefact.
 */
export function formatMetricValue(metric: Metric | SeatsMetric): string | null {
  if (!hasDisplay(metric)) return null
  if ('roundsToZero' in metric && metric.roundsToZero === true) return '<$1'
  return formatDisplay(metric.display)
}

export type Direction = 'increase' | 'decrease' | 'unchanged'

/**
 * Read from the sign the server sent, not computed from two values.
 *
 * Exposed so direction can be stated in words as well as shown by an arrow: colour and glyph alone
 * must never be the only carrier of meaning (AC-08.6).
 */
export function direction(display: Display): Direction {
  if (display.value.startsWith('-')) return 'decrease'
  return /[1-9]/.test(display.value) ? 'increase' : 'unchanged'
}

const DIRECTION_GLYPH: Record<Direction, string> = {
  increase: '▲',
  decrease: '▼',
  unchanged: '■',
}

const DIRECTION_WORD: Record<Direction, string> = {
  increase: 'up',
  decrease: 'down',
  unchanged: 'unchanged',
}

export function comparisonGlyph(display: Display): string {
  return DIRECTION_GLYPH[direction(display)]
}

export function comparisonWord(display: Display): string {
  return DIRECTION_WORD[direction(display)]
}

/**
 * What a delta is measured against.
 *
 * This is a property of *where* the comparison is rendered, not of its kind: `kind` names the units
 * (percent, percentage points, dollars, count) and says nothing about the population. A KPI card
 * compares against the previous period, while a comparison-table row compares against the
 * organisation benchmark (contract §5.4) — labelling a row "vs previous period" would name the wrong
 * population entirely, which is why the caller states the basis.
 */
export type ComparisonBasis = 'previousPeriod' | 'organisationBenchmark'

const BASIS_LABEL: Record<ComparisonBasis, string> = {
  previousPeriod: 'vs previous period',
  organisationBenchmark: 'vs organisation benchmark',
}

export function comparisonBasisLabel(basis: ComparisonBasis): string {
  return BASIS_LABEL[basis]
}

/** True when a defined display value represents zero, read from the string, never parsed. */
export function isZeroDisplay(display: Display): boolean {
  return /^-?0(\.0+)?$/.test(display.value)
}

/** `2026-08-31` as `31 Aug 2026`, in UTC. Never parsed through the local timezone. */
export function formatUtcDate(date: string): string {
  const at = Date.parse(`${date}T00:00:00Z`)
  if (Number.isNaN(at)) return date
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(at)
}

/** An inclusive range, collapsing a shared month or year: `2–31 Aug 2026`. */
export function formatUtcRange(from: string, to: string): string {
  const start = formatUtcDate(from)
  const end = formatUtcDate(to)
  if (start === end) return start
  const [startDay, startMonth, startYear] = start.split(' ')
  const [endDay, endMonth, endYear] = end.split(' ')
  if (startYear === endYear && startMonth === endMonth) {
    return `${startDay}–${endDay} ${endMonth} ${endYear}`
  }
  if (startYear === endYear) return `${startDay} ${startMonth} – ${end}`
  return `${start} – ${end}`
}

/** Cents to whole dollars, for a chart axis label. Presentation scaling, not a reported metric. */
export function centsToDollarLabel(cents: number): string {
  const dollars = cents / 100
  const magnitude = Number.isInteger(dollars) ? `${dollars}` : dollars.toFixed(2)
  return `$${groupDigits(magnitude)}`
}

export function formatCount(value: number): string {
  return groupDigits(`${value}`)
}
