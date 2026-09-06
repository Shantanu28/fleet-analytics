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
