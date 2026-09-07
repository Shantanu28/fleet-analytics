package com.fleet.analytics.metrics.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A half-open UTC interval {@code [startInclusive, endExclusive)} (contract 1.1). Both bounds are
 * UTC midnight: the demo reports complete UTC days only (contract 1.3.8), so a window that began or
 * ended mid-day could not be answered from day-grain coverage metadata.
 *
 * <p>Half-open is the whole point of the type. An event at {@code endExclusive} is outside the
 * window — that is what makes {@code dataThrough} exclusive, what excludes a denial at {@code end}
 * (contract 6.4, case N7), and what lets the previous period abut the current one without
 * double-counting the boundary instant.
 */
public record DateWindow(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {

    public DateWindow {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        // Normalise to UTC before checking anything. The JDBC driver materialises a timestamptz in
        // the JVM's own zone, so an instant that is genuinely UTC midnight can arrive as
        // 2026-02-04T04:00+04:00. Comparing the textual offset would reject it; worse, calling
        // toLocalDate() on it would name the wrong day in any zone west of UTC and silently shift
        // every window boundary. The bounds are stored normalised so days() and equality are stable
        // wherever the value came from.
        startInclusive = startInclusive.withOffsetSameInstant(ZoneOffset.UTC);
        endExclusive = endExclusive.withOffsetSameInstant(ZoneOffset.UTC);
        requireUtcMidnight(startInclusive, "startInclusive");
        requireUtcMidnight(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException("endExclusive must follow startInclusive");
        }
    }

    private static void requireUtcMidnight(OffsetDateTime normalised, String name) {
        if (!normalised.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException(
                    name + " must fall on a UTC midnight, was " + normalised);
        }
    }

    /** The UTC calendar day an instant falls in, whatever zone it was materialised in. */
    public static LocalDate utcDateOf(OffsetDateTime instant) {
        return instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    /** {@code lastDayInclusive} is the UI's inclusive end date; it maps to end + 1 day (contract 1.1). */
    public static DateWindow ofInclusiveDates(LocalDate firstDay, LocalDate lastDayInclusive) {
        return new DateWindow(atUtcMidnight(firstDay), atUtcMidnight(lastDayInclusive.plusDays(1)));
    }

    public static DateWindow ofDays(LocalDate firstDay, LocalDate firstExcludedDay) {
        return new DateWindow(atUtcMidnight(firstDay), atUtcMidnight(firstExcludedDay));
    }

    public static OffsetDateTime atUtcMidnight(LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    public LocalDate firstDay() {
        return startInclusive.toLocalDate();
    }

    /** The last day actually inside the window — what the UI shows as the selected end date. */
    public LocalDate lastDayInclusive() {
        return endExclusive.toLocalDate().minusDays(1);
    }

    public long lengthInDays() {
        return ChronoUnit.DAYS.between(startInclusive, endExclusive);
    }

    /** Every complete UTC day the window spans, in order — the grain of coverage metadata (A.5). */
    public List<LocalDate> days() {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = firstDay(); day.isBefore(endExclusive.toLocalDate()); day = day.plusDays(1)) {
            days.add(day);
        }
        return List.copyOf(days);
    }

    /** {@code [start - (end - start), start)} — the immediately preceding equal block (contract 1.1). */
    public DateWindow immediatelyBefore() {
        return new DateWindow(startInclusive.minusDays(lengthInDays()), startInclusive);
    }

    /** {@code [start - days, start)} — the non-overlapping trailing baseline of contract 6.2. */
    public DateWindow precedingDays(int days) {
        return new DateWindow(startInclusive.minusDays(days), startInclusive);
    }

    public boolean contains(OffsetDateTime instant) {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
