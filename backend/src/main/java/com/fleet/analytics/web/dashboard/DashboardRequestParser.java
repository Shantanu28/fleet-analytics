package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import com.fleet.analytics.metrics.model.ScopeFilters;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns raw query parameters into a validated {@link DashboardSelection}, using the organisation's
 * published coverage to decide what is answerable.
 *
 * <p>Two distinctions this class exists to preserve:
 *
 * <ul>
 *   <li><b>Absent is not empty.</b> {@code ?from=} is a supplied value that happens to be blank, and
 *       it is rejected. Treating it as omitted would silently answer a different question than the
 *       one asked — the caller would get the 30-day default and never learn their input was lost.
 *   <li><b>Rejection is not partial truth.</b> A range reaching outside published coverage is
 *       refused outright rather than answered for the covered part, because absence outside coverage
 *       means unknown, never zero (contract 1.5).
 * </ul>
 *
 * <p>No organisation parameter is read here or anywhere: tenant scope comes from the verified token.
 */
@Component
public class DashboardRequestParser {

    /** Contract 1.5: a period must be fully covered, so the default is bounded by dataThrough. */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /**
     * Exactly four digits, two digits, two digits. {@code LocalDate.parse} alone is too permissive:
     * ISO_LOCAL_DATE accepts extended years such as {@code +999999999-12-31}, which parses cleanly
     * and then overflows in the inclusive-end {@code plusDays(1)} arithmetic — surfacing as an
     * unhandled server fault rather than the rejected input it is. Shape is checked before parsing
     * so nothing out of range ever reaches the arithmetic.
     */
    private static final Pattern CALENDAR_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * @param parameters raw query parameters; a key present with an empty value is a supplied empty
     *     value, and a key that is absent is an omission. The two are never conflated.
     */
    public DashboardSelection parse(Map<String, String> parameters, Coverage publication) {
        DateWindow window = resolveWindow(parameters, publication);
        ScopeFilters filters = new ScopeFilters(
                uuidParameter(parameters, "teamId"), uuidParameter(parameters, "repositoryId"));
        return new DashboardSelection(window, filters, resolveGrouping(parameters));
    }

    private DateWindow resolveWindow(Map<String, String> parameters, Coverage publication) {
        boolean hasFrom = parameters.containsKey("from");
        boolean hasTo = parameters.containsKey("to");

        if (!hasFrom && !hasTo) {
            return withinCoverage(defaultWindow(publication), publication);
        }

        // Shape is validated before completeness, because the two failures answer different
        // questions. `?from=` is a supplied value that is malformed; `?from=2026-01-16` alone is a
        // well-formed value with its partner missing. Checking completeness first would report the
        // empty value as a missing companion and hide the actual mistake.
        LocalDate from = hasFrom ? date(parameters.get("from"), "from") : null;
        LocalDate to = hasTo ? date(parameters.get("to"), "to") : null;
        if (from == null || to == null) {
            throw new InvalidDashboardSelectionException(SelectionProblem.INCOMPLETE_DATE_RANGE,
                    "Supply both a start and an end date, or neither.");
        }

        if (from.isAfter(to)) {
            throw new InvalidDashboardSelectionException(SelectionProblem.REVERSED_DATE_RANGE,
                    "The start date must not be after the end date.");
        }
        return withinCoverage(DateWindow.ofInclusiveDates(from, to), publication);
    }

    /** The last 30 complete UTC days: dataThrough is exclusive, so it is already the day after. */
    private DateWindow defaultWindow(Coverage publication) {
        return new DateWindow(
                publication.dataThrough().minusDays(DEFAULT_RANGE_DAYS), publication.dataThrough());
    }

    private DateWindow withinCoverage(DateWindow window, Coverage publication) {
        boolean covered = !window.startInclusive().isBefore(publication.dataAvailableFrom())
                && !window.endExclusive().isAfter(publication.dataThrough());
        if (!covered) {
            throw new InvalidDashboardSelectionException(SelectionProblem.RANGE_OUTSIDE_COVERAGE,
                    "The selected dates reach outside the reported data range.");
        }
        return window;
    }

    /**
     * Strict {@code YYYY-MM-DD}, calendar validity included: 2026-02-30 is a format error, not a
     * date that silently rolls into March.
     */
    private LocalDate date(String value, String parameterName) {
        if (value == null || value.isBlank()) {
            throw new InvalidDashboardSelectionException(SelectionProblem.INVALID_DATE_FORMAT,
                    "The " + parameterName + " date is empty; supply a date as YYYY-MM-DD.");
        }
        if (!CALENDAR_DATE.matcher(value).matches()) {
            throw new InvalidDashboardSelectionException(SelectionProblem.INVALID_DATE_FORMAT,
                    "The " + parameterName + " date is not a valid YYYY-MM-DD date.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidDashboardSelectionException(SelectionProblem.INVALID_DATE_FORMAT,
                    "The " + parameterName + " date is not a valid YYYY-MM-DD date.");
        }
    }

    private Grouping resolveGrouping(Map<String, String> parameters) {
        if (!parameters.containsKey("grouping")) {
            return Grouping.TEAMS;
        }
        Grouping grouping = Grouping.fromWireName(parameters.get("grouping"));
        if (grouping == null) {
            throw new InvalidDashboardSelectionException(SelectionProblem.INVALID_GROUPING,
                    "The table grouping must be either teams or repositories.");
        }
        return grouping;
    }

    /**
     * A malformed identifier is reported as an unknown filter, not as a distinct format error: a
     * caller must not be able to distinguish "not a UUID" from "not yours" from "does not exist".
     */
    private UUID uuidParameter(Map<String, String> parameters, String name) {
        if (!parameters.containsKey(name)) {
            return null;
        }
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new InvalidDashboardSelectionException(SelectionProblem.UNKNOWN_FILTER,
                    "The requested filter is not available.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidDashboardSelectionException(SelectionProblem.UNKNOWN_FILTER,
                    "The requested filter is not available.");
        }
    }
}
