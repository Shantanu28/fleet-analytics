import { describe, expect, it } from 'vitest'
import { lastCompleteUtcDate } from '../api/coverage'

/**
 * `dataThrough` is exclusive, so the reported cutoff is the day before it (AC-01.3). Boundaries are
 * where an off-by-one would otherwise hide.
 */
describe('lastCompleteUtcDate', () => {
  it('reports the day before an exclusive dataThrough', () => {
    expect(lastCompleteUtcDate('2026-02-15T00:00:00Z')).toBe('2026-02-14')
  })

  it('crosses a month boundary', () => {
    expect(lastCompleteUtcDate('2026-03-01T00:00:00Z')).toBe('2026-02-28')
    expect(lastCompleteUtcDate('2026-05-01T00:00:00Z')).toBe('2026-04-30')
  })

  it('crosses a year boundary', () => {
    expect(lastCompleteUtcDate('2026-01-01T00:00:00Z')).toBe('2025-12-31')
  })

  it('handles a leap year February', () => {
    expect(lastCompleteUtcDate('2028-03-01T00:00:00Z')).toBe('2028-02-29')
    expect(lastCompleteUtcDate('2027-03-01T00:00:00Z')).toBe('2027-02-28')
  })

  it('is unaffected by the local time zone', () => {
    // An offset-bearing instant still resolves against UTC, not the runner's zone.
    expect(lastCompleteUtcDate('2026-03-01T00:00:00+00:00')).toBe('2026-02-28')
  })

  it('returns null rather than a wrong date for an unparseable value', () => {
    expect(lastCompleteUtcDate('not-a-date')).toBeNull()
    expect(lastCompleteUtcDate('')).toBeNull()
  })
})
