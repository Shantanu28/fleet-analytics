import { describe, expect, it } from 'vitest'
import {
  centsToDollarLabel,
  comparisonGlyph,
  comparisonWord,
  direction,
  formatCount,
  formatDisplay,
  formatMetricValue,
  formatUtcDate,
  formatUtcRange,
} from '../dashboard/presentation'
import { display, okMetric, unavailableMetric } from './dashboardFixtures'

describe('formatting server display values', () => {
  /**
   * The scale the server chose carries meaning: "23" is a whole-dollar spend total and "23.00" is a
   * unit cost. Reformatting either through Number() would collapse them into the same thing.
   */
  it.each([
    [display('1', 'count'), '1'],
    [display('1204', 'count'), '1,204'],
    [display('20000', 'count'), '20,000'],
    [display('0', 'count'), '0'],
    [display('50.0', 'percent'), '50.0%'],
    [display('0.0', 'percent'), '0.0%'],
    [display('100.0', 'percent'), '100.0%'],
    [display('23.00', 'usd'), '$23.00'],
    [display('23', 'usd'), '$23'],
    [display('0.00', 'usd'), '$0.00'],
    [display('11800', 'usd'), '$11,800'],
  ])('renders %o unchanged in precision', (value, expected) => {
    expect(formatDisplay(value)).toBe(expected)
  })

  it('places a negative sign outside the currency symbol', () => {
    expect(formatDisplay(display('-3.10', 'usd'))).toBe('-$3.10')
    expect(formatDisplay(display('-5850.25', 'usd'))).toBe('-$5,850.25')
  })

  it('signs a comparison but never a value', () => {
    expect(formatDisplay(display('2.4', 'percentagePoints'), true)).toBe('+2.4 pp')
    expect(formatDisplay(display('-16.7', 'percentagePoints'), true)).toBe('-16.7 pp')
    expect(formatDisplay(display('-50.0', 'percent'), true)).toBe('-50.0%')
    expect(formatDisplay(display('4', 'count'), true)).toBe('+4')
    // A value, not a comparison: no leading plus.
    expect(formatDisplay(display('4', 'count'))).toBe('4')
  })

  it('does not sign a zero comparison as an increase', () => {
    expect(formatDisplay(display('0.0', 'percentagePoints'), true)).toBe('+0.0 pp')
    expect(direction(display('0.0', 'percentagePoints'))).toBe('unchanged')
  })
})

describe('direction', () => {
  it('reads the sign the server sent rather than comparing two values', () => {
    expect(direction(display('2.4', 'percentagePoints'))).toBe('increase')
    expect(direction(display('-2.4', 'percentagePoints'))).toBe('decrease')
    expect(direction(display('0.0', 'percent'))).toBe('unchanged')
    expect(direction(display('0', 'count'))).toBe('unchanged')
  })

  /** Meaning must survive without colour: a glyph and a word accompany every delta (AC-08.6). */
  it('offers both a glyph and a word for every direction', () => {
    expect(comparisonGlyph(display('2.4', 'percentagePoints'))).toBe('▲')
    expect(comparisonWord(display('2.4', 'percentagePoints'))).toBe('up')
    expect(comparisonGlyph(display('-2.4', 'percentagePoints'))).toBe('▼')
    expect(comparisonWord(display('-2.4', 'percentagePoints'))).toBe('down')
    expect(comparisonWord(display('0.0', 'percent'))).toBe('unchanged')
  })
})

describe('metric values', () => {
  it('returns null for an unavailable metric so a caller cannot print a blank as zero', () => {
    expect(formatMetricValue(unavailableMetric('no_denominator', 'x', 'y'))).toBeNull()
    expect(formatMetricValue(okMetric('0.0', 'percent'))).toBe('0.0%')
  })

  /** A positive amount rounding to zero reads as "<$1"; a real zero must still read as $0. */
  it('honours the sub-dollar indicator only when the server sets it', () => {
    expect(
      formatMetricValue({ state: 'ok', display: display('0', 'usd'), roundsToZero: true }),
    ).toBe('<$1')
    expect(
      formatMetricValue({ state: 'ok', display: display('0', 'usd'), roundsToZero: false }),
    ).toBe('$0')
    expect(formatMetricValue({ state: 'ok', display: display('23', 'usd') })).toBe('$23')
  })
})

describe('UTC dates', () => {
  it('formats a date in UTC regardless of the local timezone', () => {
    expect(formatUtcDate('2026-08-31')).toBe('31 Aug 2026')
    expect(formatUtcDate('2026-01-01')).toBe('1 Jan 2026')
  })

  it('leaves an unparseable date alone rather than inventing one', () => {
    expect(formatUtcDate('31-08-2026')).toBe('31-08-2026')
  })

  it('collapses a shared month or year in an inclusive range', () => {
    expect(formatUtcRange('2026-08-02', '2026-08-31')).toBe('2–31 Aug 2026')
    expect(formatUtcRange('2026-06-03', '2026-08-31')).toBe('3 Jun – 31 Aug 2026')
    expect(formatUtcRange('2025-12-26', '2026-01-02')).toBe('26 Dec 2025 – 2 Jan 2026')
    expect(formatUtcRange('2026-08-31', '2026-08-31')).toBe('31 Aug 2026')
  })
})

describe('chart and count labels', () => {
  it('converts cents to a dollar axis label', () => {
    expect(centsToDollarLabel(0)).toBe('$0')
    expect(centsToDollarLabel(30000)).toBe('$300')
    expect(centsToDollarLabel(150)).toBe('$1.50')
    expect(centsToDollarLabel(972500)).toBe('$9,725')
  })

  it('groups plain counts', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(20000)).toBe('20,000')
  })
})
