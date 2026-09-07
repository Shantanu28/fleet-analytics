package com.fleet.analytics.metrics.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleet.analytics.metrics.model.BudgetSample;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.Severity;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Contract 8.5 cases B1 to B8, plus the month-boundary rule and independent budget scoping. */
class BudgetRiskRuleTest {

    private static final YearMonth FEBRUARY = YearMonth.of(2026, 2);
    private static final RuleScope ORGANISATION = RuleScope.organisation("Contract Fixture Org");

    private final BudgetRiskRule rule = new BudgetRiskRule();

    private static BigInteger dollars(long amount) {
        return BigInteger.valueOf(amount * 100);
    }

    private static BudgetSample sample(long budgetDollars, long mtdDollars, int elapsedDays) {
        return new BudgetSample(dollars(budgetDollars), dollars(mtdDollars), elapsedDays, 28);
    }

    private FindingCandidate findingFrom(RuleEvaluation evaluation) {
        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        RuleEvaluation.Completed completed = (RuleEvaluation.Completed) evaluation;
        assertThat(completed.candidates()).hasSize(1);
        return completed.candidates().getFirst();
    }

    private static EvaluationState stateOf(RuleEvaluation evaluation) {
        assertThat(evaluation).isInstanceOf(RuleEvaluation.Unavailable.class);
        return ((RuleEvaluation.Unavailable) evaluation).limit().state();
    }

    // --- Triggering and severity (contract 8.5 B1, B2, B3, B8) ----------------------------------

    /**
     * B1 through B3 and B8 share one arithmetic and differ only at the boundary. Both thresholds are
     * strict — 10.0% does not trigger and 20.0% is MEDIUM, not HIGH — which is exactly where a
     * rounded forecast would put a value on the wrong side.
     */
    @ParameterizedTest(name = "MTD ${1} over ${0} in {2}/28 days")
    @CsvSource({
        "10000, 5900, 14, MEDIUM",   // B1: forecast $11,800, overrun 18.0%
        "10000, 6250, 14, HIGH",     // B2: forecast $12,500, overrun 25.0%
        "10000, 6000, 14, MEDIUM",   // B8: forecast $12,000, overrun exactly 20.0% -- not HIGH
    })
    void anOverrunAboveTenPercentTriggersAtTheApprovedSeverity(
            long budget, long mtd, int elapsed, Severity expected) {
        FindingCandidate finding =
                findingFrom(rule.evaluate(ORGANISATION, FEBRUARY, sample(budget, mtd, elapsed)));

        assertThat(finding.severity()).isEqualTo(expected);
    }

    /** B3: a forecast of exactly $11,000 against $10,000 is 10.0%, which is not {@code > 10%}. */
    @Test
    void anOverrunOfExactlyTenPercentDoesNotTrigger() {
        RuleEvaluation evaluation =
                rule.evaluate(ORGANISATION, FEBRUARY, sample(10000, 5500, 14));

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        assertThat(((RuleEvaluation.Completed) evaluation).candidates()).isEmpty();
    }

    @Test
    void theDisplayedOverrunAndForecastMatchTheContractsWorkedFigures() {
        BudgetSample sample = sample(10000, 5900, 14);

        FindingCandidate finding = findingFrom(rule.evaluate(ORGANISATION, FEBRUARY, sample));

        assertThat(finding.displayMagnitude().value()).isEqualTo("18.0");
        assertThat(BudgetRiskRule.forecastDollars(sample).value()).isEqualTo("11800");
    }

    /**
     * The threshold is decided on integers, never on the rounded percentage. An overrun of
     * 10.04% rounds to 10.0 for display yet is genuinely above the trigger, so it must fire.
     */
    @Test
    void aTriggerJustAboveTheThresholdFiresEvenThoughItDisplaysAsTenPercent() {
        // budget $10,000, elapsed 10 of 28, MTD $3,930 -> forecast $11,004 -> overrun 10.04%.
        FindingCandidate finding =
                findingFrom(rule.evaluate(ORGANISATION, FEBRUARY, sample(10000, 3930, 10)));

        assertThat(finding.displayMagnitude().value()).isEqualTo("10.0");
        assertThat(finding.severity()).isEqualTo(Severity.MEDIUM);
    }

    // --- Configuration and history states (B4, B6, B7) --------------------------------------------

    /** B6: no budget row at all. Reported, never treated as "nothing to worry about". */
    @Test
    void aScopeWithNoConfiguredBudgetIsNotEvaluated() {
        RuleEvaluation evaluation = rule.evaluate(
                RuleScope.team(UUID.randomUUID(), "Platform"), FEBRUARY, null);

        assertThat(stateOf(evaluation)).isEqualTo(EvaluationState.NOT_EVALUATED);
        assertThat(((RuleEvaluation.Unavailable) evaluation).limit().explanation().code())
                .isEqualTo("no_budget_configured");
    }

    /** B7: a budget of zero or less is a configuration error, and produces no percentage at all. */
    @ParameterizedTest(name = "budget {0} cents")
    @CsvSource({"0", "-1", "-500000"})
    void aNonPositiveBudgetIsAnInvalidConfiguration(long budgetCents) {
        BudgetSample sample = new BudgetSample(
                BigInteger.valueOf(budgetCents), dollars(6000), 14, 28);

        assertThat(stateOf(rule.evaluate(ORGANISATION, FEBRUARY, sample)))
                .isEqualTo(EvaluationState.INVALID_BUDGET_CONFIGURATION);
    }

    /** B4: two elapsed days is too little to project a month from; three is the minimum. */
    @Test
    void fewerThanThreeElapsedDaysIsInsufficientHistory() {
        assertThat(stateOf(rule.evaluate(ORGANISATION, FEBRUARY, sample(10000, 9000, 2))))
                .isEqualTo(EvaluationState.INSUFFICIENT_HISTORY);
        assertThat(rule.evaluate(ORGANISATION, FEBRUARY, sample(10000, 9000, 3)))
                .isInstanceOf(RuleEvaluation.Completed.class);
    }

    /** Contract 5.3: a repository filter removes budget evaluation, team filter or not. */
    @Test
    void aRepositoryScopeMakesBudgetEvaluationUnavailable() {
        assertThat(stateOf(rule.unavailableForRepositoryScope(ORGANISATION)))
                .isEqualTo(EvaluationState.UNAVAILABLE_FOR_SCOPE);
    }

    // --- The evaluated month (B5, contract 6.1) -----------------------------------------------------

    /**
     * B5: with {@code dataThrough} at midnight on 1 February the last complete day is 31 January, so
     * the evaluated month is January in full — not an empty February with nothing to forecast from.
     */
    @Test
    void theEvaluatedMonthIsTheOneContainingTheLastCompleteDay() {
        assertThat(BudgetRiskRule.evaluatedMonth(LocalDate.of(2026, 1, 31)))
                .isEqualTo(YearMonth.of(2026, 1));
        assertThat(BudgetRiskRule.evaluatedMonth(LocalDate.of(2026, 2, 3)))
                .isEqualTo(YearMonth.of(2026, 2));
    }

    /** The finding carries its own month, never the selected dashboard range (contract 6.1, 6.5). */
    @Test
    void theFindingCarriesItsOwnEvaluatedMonth() {
        FindingCandidate finding =
                findingFrom(rule.evaluate(ORGANISATION, FEBRUARY, sample(10000, 5900, 14)));

        assertThat(finding.evaluationFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(finding.evaluationTo()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(finding.identity().evaluationPeriodKey()).isEqualTo("2026-02");
    }

    /** The contract fixture itself: $2,000 budget, 3 elapsed days, $4.00 spent -> far below trigger. */
    @Test
    void theContractFixtureBudgetProducesNoFinding() {
        BudgetSample fixture = new BudgetSample(
                BigInteger.valueOf(200000), BigInteger.valueOf(400), 3, 28);

        RuleEvaluation evaluation = rule.evaluate(ORGANISATION, FEBRUARY, fixture);

        assertThat(evaluation).isInstanceOf(RuleEvaluation.Completed.class);
        assertThat(((RuleEvaluation.Completed) evaluation).candidates()).isEmpty();
        assertThat(BudgetRiskRule.forecastDollars(fixture).value()).isEqualTo("37");
    }
}
