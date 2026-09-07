package com.fleet.analytics.web.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleet.analytics.data.Coverage;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DateWindow;
import com.fleet.analytics.metrics.model.Grouping;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Selection parsing against a fixed publication. No database and no HTTP: the parser's whole job is
 * deciding what a set of raw strings means, and it must be provable without either.
 *
 * <p>Status codes are deliberately not asserted — the HTTP adapter is slice D. What is asserted is
 * the typed reason, because that is what the adapter will map and what the client keys its message on.
 */
class DashboardRequestParserTest {

    /** The contract fixture's published interval (contract 7). */
    private static final Coverage PUBLICATION = new Coverage(
            OffsetDateTime.of(2025, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 2, 4, 0, 0, 0, 0, ZoneOffset.UTC),
            "contract-fixture-1");

    private final DashboardRequestParser parser = new DashboardRequestParser();

    private static Map<String, String> params(String... keyValuePairs) {
        Map<String, String> parameters = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            parameters.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return parameters;
    }

    private SelectionProblem problemFrom(Map<String, String> parameters) {
        try {
            parser.parse(parameters, PUBLICATION);
            throw new AssertionError("expected the selection to be rejected");
        } catch (InvalidDashboardSelectionException e) {
            return e.problem();
        }
    }

    @Test
    void omittingBothDatesSelectsTheLastThirtyCompleteDays() {
        DashboardSelection selection = parser.parse(params(), PUBLICATION);

        assertThat(selection.window().firstDay()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(selection.window().lengthInDays()).isEqualTo(30);
        // dataThrough is exclusive, so the last complete day reported is 3 February.
        assertThat(selection.window().lastDayInclusive()).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    @Test
    void theInclusiveEndDateBecomesExclusiveMidnightOfTheFollowingDay() {
        DashboardSelection selection =
                parser.parse(params("from", "2026-01-16", "to", "2026-01-31"), PUBLICATION);

        assertThat(selection.window().startInclusive())
                .isEqualTo(OffsetDateTime.of(2026, 1, 16, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(selection.window().endExclusive())
                .isEqualTo(OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(selection.window().lengthInDays()).isEqualTo(16);
    }

    @Test
    void theSelectedRangeImpliesAnEqualLengthPreviousPeriod() {
        DashboardSelection selection =
                parser.parse(params("from", "2026-01-16", "to", "2026-01-31"), PUBLICATION);

        DateWindow previous = selection.previousWindow();
        assertThat(previous.firstDay()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(previous.lastDayInclusive()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    /**
     * Each row is a different way of getting the dates wrong, and each must stay distinguishable:
     * the frontend keys a controlled message on the problem type, so collapsing these into one
     * generic failure would degrade every one of them to "invalid input".
     */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "2026-01-16, '',           INVALID_DATE_FORMAT",
        "'',         2026-01-31,   INVALID_DATE_FORMAT",
        "2026-02-30, 2026-03-05,   INVALID_DATE_FORMAT",
        "2026-1-5,   2026-01-31,   INVALID_DATE_FORMAT",
        "16-01-2026, 2026-01-31,   INVALID_DATE_FORMAT",
        "2026-01-31, 2026-01-16,   REVERSED_DATE_RANGE",
        "2025-11-01, 2026-01-31,   RANGE_OUTSIDE_COVERAGE",
        "2026-01-16, 2026-02-04,   RANGE_OUTSIDE_COVERAGE",
    })
    void malformedRangesAreRejectedWithDistinctReasons(String from, String to, SelectionProblem expected) {
        assertThat(problemFrom(params("from", from, "to", to))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "only {0} supplied")
    @ValueSource(strings = {"from", "to"})
    void oneDateWithoutTheOtherIsAnIncompleteRangeRatherThanADefault(String suppliedParameter) {
        assertThat(problemFrom(params(suppliedParameter, "2026-01-16")))
                .isEqualTo(SelectionProblem.INCOMPLETE_DATE_RANGE);
    }

    /**
     * An empty value is a supplied value. Reading it as an omission would answer the 30-day default
     * and never tell the caller their input was discarded.
     */
    @Test
    void anEmptyDateIsRejectedRatherThanTreatedAsOmitted() {
        Map<String, String> parameters = params("from", "", "to", "");

        assertThat(problemFrom(parameters)).isEqualTo(SelectionProblem.INVALID_DATE_FORMAT);
    }

    /**
     * ISO_LOCAL_DATE accepts extended years, which parse cleanly and then overflow when the
     * inclusive end date is advanced by a day — an unhandled server fault for what is plainly bad
     * input. The shape is checked before any arithmetic can run.
     */
    @ParameterizedTest(name = "from=''{0}''")
    @ValueSource(strings = {
        "+999999999-12-31", "-999999999-01-01", "+2026-01-16", "999999999-12-31", "12026-01-16",
    })
    void extendedYearInputsAreRejectedBeforeTheEndDateArithmetic(String from) {
        assertThat(problemFrom(params("from", from, "to", "2026-01-31")))
                .isEqualTo(SelectionProblem.INVALID_DATE_FORMAT);
        // Also as the end date, which is the bound that actually gets incremented.
        assertThat(problemFrom(params("from", "2026-01-16", "to", from)))
                .isEqualTo(SelectionProblem.INVALID_DATE_FORMAT);
    }

    /**
     * An empty value is malformed whether or not its partner was supplied. Deciding completeness
     * first would report a blank date as a missing companion and hide the real mistake — while a
     * genuinely well-formed lone date is still an incomplete range.
     */
    @ParameterizedTest(name = "{0} empty, companion absent")
    @ValueSource(strings = {"from", "to"})
    void anEmptyDateIsMalformedEvenWhenItsCompanionIsAbsent(String parameter) {
        assertThat(problemFrom(params(parameter, "")))
                .isEqualTo(SelectionProblem.INVALID_DATE_FORMAT);
        assertThat(problemFrom(params(parameter, "   ")))
                .isEqualTo(SelectionProblem.INVALID_DATE_FORMAT);
        assertThat(problemFrom(params(parameter, "2026-01-16")))
                .isEqualTo(SelectionProblem.INCOMPLETE_DATE_RANGE);
    }

    @Test
    void theLastDayOfCoverageIsSelectable() {
        DashboardSelection selection =
                parser.parse(params("from", "2026-01-16", "to", "2026-02-03"), PUBLICATION);

        assertThat(selection.window().endExclusive()).isEqualTo(PUBLICATION.dataThrough());
    }

    /** 90 days is a preset, not a maximum: any fully covered range is valid (contract 1.5). */
    @Test
    void aRangeLongerThanNinetyDaysIsAcceptedWhenFullyCovered() {
        Coverage wide = new Coverage(
                OffsetDateTime.of(2025, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 2, 4, 0, 0, 0, 0, ZoneOffset.UTC), "wide");

        DashboardSelection selection = parser.parse(params("from", "2025-06-01", "to", "2026-02-03"), wide);

        assertThat(selection.window().lengthInDays()).isGreaterThan(90);
    }

    @Test
    void groupingDefaultsToTeamsOnlyWhenTheParameterIsAbsent() {
        assertThat(parser.parse(params(), PUBLICATION).grouping()).isEqualTo(Grouping.TEAMS);
        assertThat(parser.parse(params("grouping", "repositories"), PUBLICATION).grouping())
                .isEqualTo(Grouping.REPOSITORIES);
    }

    @ParameterizedTest(name = "grouping=''{0}''")
    @ValueSource(strings = {"", "Teams", "nonsense", "team"})
    void anUnrecognisedGroupingIsRejectedRatherThanDefaulted(String grouping) {
        assertThat(problemFrom(params("grouping", grouping)))
                .isEqualTo(SelectionProblem.INVALID_GROUPING);
    }

    @Test
    void wellFormedFilterIdentifiersAreCarriedThrough() {
        UUID team = UUID.randomUUID();
        UUID repository = UUID.randomUUID();

        DashboardSelection selection = parser.parse(
                params("teamId", team.toString(), "repositoryId", repository.toString()), PUBLICATION);

        assertThat(selection.filters().teamId()).isEqualTo(team);
        assertThat(selection.filters().repositoryId()).isEqualTo(repository);
        assertThat(selection.filters().hasAnyScope()).isTrue();
    }

    /**
     * A malformed id reports the same reason a foreign or nonexistent one will (slice B's
     * FilterResolver): the caller must not learn anything about which ids exist or whose they are.
     */
    @ParameterizedTest(name = "teamId=''{0}''")
    @ValueSource(strings = {"", "   ", "not-a-uuid", "1d000000-0000-0000-0000"})
    void aMalformedFilterIdentifierIsIndistinguishableFromAnUnknownOne(String teamId) {
        assertThat(problemFrom(params("teamId", teamId))).isEqualTo(SelectionProblem.UNKNOWN_FILTER);
    }

    @Test
    void noFilterMeansNoRestrictionRatherThanNoRows() {
        DashboardSelection selection = parser.parse(params(), PUBLICATION);

        assertThat(selection.filters().hasTeam()).isFalse();
        assertThat(selection.filters().hasRepository()).isFalse();
        assertThat(selection.filters().hasAnyScope()).isFalse();
    }
}
