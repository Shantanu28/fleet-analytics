/**
 * `dataThrough` is an *exclusive* UTC-midnight instant, so the last day actually reported is the
 * day before it (contract §1.1; AC-01.3). Displaying `dataThrough` itself would claim a day of
 * coverage the dataset does not have.
 *
 * UTC has no daylight saving, so subtracting exactly one day in milliseconds is exact, and month,
 * year and leap-year boundaries fall out of the arithmetic.
 */
export function lastCompleteUtcDate(dataThrough: string): string | null {
  const at = Date.parse(dataThrough)
  if (Number.isNaN(at)) return null
  return new Date(at - 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

const MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000

/**
 * Shifts a `YYYY-MM-DD` UTC date by whole days.
 *
 * UTC has no daylight saving, so millisecond arithmetic is exact and month, year and leap-year
 * boundaries fall out of it. Doing this with local dates would move the boundary by an hour twice a
 * year and quietly shift which day a figure belongs to.
 */
export function shiftUtcDate(date: string, days: number): string | null {
  const at = Date.parse(`${date}T00:00:00Z`)
  if (Number.isNaN(at)) return null
  return new Date(at + days * MILLISECONDS_PER_DAY).toISOString().slice(0, 10)
}

/**
 * The inclusive UTC range for a preset of `days`, ending at the last complete day.
 *
 * Anchored to the dataset's own `dataThrough`, never to `Date.now()`: the demo dataset is fixed, so
 * a clock-based default would drift out of coverage and start rejecting every request (AC-02.1).
 */
export function presetRange(
  dataThrough: string,
  days: number,
): { readonly from: string; readonly to: string } | null {
  const to = lastCompleteUtcDate(dataThrough)
  if (to === null || days < 1) return null
  const from = shiftUtcDate(to, -(days - 1))
  return from === null ? null : { from, to }
}
