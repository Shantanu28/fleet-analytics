package com.fleet.analytics.metrics.rules;

import com.fleet.analytics.metrics.Explanations;
import com.fleet.analytics.metrics.MetricCalculator;
import com.fleet.analytics.metrics.model.BudgetSample;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.EvaluationState;
import com.fleet.analytics.metrics.model.Explanation;
import com.fleet.analytics.metrics.model.ExactMagnitude;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.FindingIdentity;
import com.fleet.analytics.metrics.model.RuleEvaluation;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.model.RuleScope;
import com.fleet.analytics.metrics.model.RuleType;
import com.fleet.analytics.metrics.model.Severity;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/**
 * Budget risk (contract 6.1): does the month-end forecast overrun the configured budget?
 *
 * <p><b>The threshold is decided on exact integers.</b> The forecast is
 * {@code mtd / elapsed * daysInMonth} and the overrun is {@code forecast / budget - 1}, but neither
 * is ever computed as a number before the comparison. Both trigger tests reduce to comparing
 * {@code mtd * daysInMonth * 100} against {@code budget * elapsed * 110} (and {@code 120}), which is
 * exact for any inputs. A forecast rounded to dollars first would put a value sitting exactly on a
 * threshold on the wrong side of it.
 *
 * <p>Every non-triggering outcome is still an outcome. Missing configuration, an invalid budget and
 * too little history are reported as limits with their own reasons — never as an absence of risk.
 */
@Component
public class BudgetRiskRule {

    /** Contract 6.1: a finding needs overrun > 10%, and HIGH needs > 20%. */
    private static final BigInteger MEDIUM_PERCENT = BigInteger.valueOf(110);
    private static final BigInteger HIGH_PERCENT = BigInteger.valueOf(120);
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    /** Contract 6.1: fewer than three complete days is too little to project a month from. */
    static final int MINIMUM_ELAPSED_DAYS = 3;

    /**
     * @param sample null when the scope has no configured budget row at all.
     */
    public RuleEvaluation evaluate(RuleScope scope, YearMonth month, BudgetSample sample) {
        String periodKey = month.toString();

        if (sample == null) {
            return unavailable(scope, EvaluationState.NOT_EVALUATED,
                    Explanations.noBudgetConfigured(scope.scopeType()));
        }
        if (!sample.hasPositiveBudget()) {
            return unavailable(scope, EvaluationState.INVALID_BUDGET_CONFIGURATION,
                    Explanations.invalidBudgetConfiguration());
        }
        if (sample.elapsedDays() < MINIMUM_ELAPSED_DAYS) {
            return unavailable(scope, EvaluationState.INSUFFICIENT_HISTORY,
                    Explanations.insufficientBudgetHistory(sample.elapsedDays()));
        }

        // forecast/budget - 1 > 0.10  <=>  mtd * daysInMonth * 100 > budget * elapsed * 110.
        BigInteger projected = sample.mtdSpendCents()
                .multiply(BigInteger.valueOf(sample.daysInMonth())).multiply(HUNDRED);
        BigInteger budgetOverElapsed = sample.budgetCents()
                .multiply(BigInteger.valueOf(sample.elapsedDays()));

        if (projected.compareTo(budgetOverElapsed.multiply(MEDIUM_PERCENT)) <= 0) {
            return RuleEvaluation.Completed.withNoFinding(RuleType.BUDGET_RISK, scope);
        }
        Severity severity =
                projected.compareTo(budgetOverElapsed.multiply(HIGH_PERCENT)) > 0
                        ? Severity.HIGH
                        : Severity.MEDIUM;

        // overrun = (mtd * daysInMonth - budget * elapsed) / (budget * elapsed), exact.
        ExactMagnitude overrun = ExactMagnitude.of(
                sample.mtdSpendCents().multiply(BigInteger.valueOf(sample.daysInMonth()))
                        .subtract(budgetOverElapsed),
                budgetOverElapsed);

        return RuleEvaluation.Completed.with(RuleType.BUDGET_RISK, scope, new FindingCandidate(
                FindingIdentity.of(RuleType.BUDGET_RISK, scope, periodKey),
                scope, severity, overrun, displayOverrun(overrun),
                new RuleEvidence.Budget(sample),
                month.atDay(1), month.atDay(1).plusDays(sample.elapsedDays() - 1L)));
    }

    /** The overrun as a percentage, rounded exactly once from the four exact integers behind it. */
    private DisplayValue displayOverrun(ExactMagnitude overrun) {
        return DisplayValue.of(
                MetricCalculator.percentage(overrun.numerator(), overrun.denominator()),
                DisplayUnit.PERCENT);
    }

    /** The forecast in whole dollars, for the finding's own evidence line. */
    public static DisplayValue forecastDollars(BudgetSample sample) {
        BigInteger forecastCents = sample.mtdSpendCents()
                .multiply(BigInteger.valueOf(sample.daysInMonth()))
                .divide(BigInteger.valueOf(sample.elapsedDays()));
        return MetricCalculator.spendTotal(forecastCents).display();
    }

    /** Contract 5.3: budget risk is not defined under a repository filter, team filter or not. */
    public RuleEvaluation unavailableForRepositoryScope(RuleScope scope) {
        return unavailable(scope, EvaluationState.UNAVAILABLE_FOR_SCOPE,
                Explanations.budgetUnavailableUnderRepositoryFilter());
    }

    private RuleEvaluation unavailable(
            RuleScope scope, EvaluationState state, Explanation why) {
        return new RuleEvaluation.Unavailable(
                new EvaluationLimit(RuleType.BUDGET_RISK, scope, state, why));
    }

    /** The month whose budget applies: the one containing the last complete day (contract 6.1). */
    public static YearMonth evaluatedMonth(LocalDate lastCompleteDay) {
        return YearMonth.from(lastCompleteDay);
    }
}
