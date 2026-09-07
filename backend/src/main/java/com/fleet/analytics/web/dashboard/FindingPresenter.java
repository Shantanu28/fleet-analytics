package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.metrics.MetricCalculator;
import com.fleet.analytics.metrics.model.AttentionResult;
import com.fleet.analytics.metrics.model.BudgetSample;
import com.fleet.analytics.metrics.model.DashboardSelection;
import com.fleet.analytics.metrics.model.DisplayUnit;
import com.fleet.analytics.metrics.model.DisplayValue;
import com.fleet.analytics.metrics.model.EvaluationLimit;
import com.fleet.analytics.metrics.model.FindingCandidate;
import com.fleet.analytics.metrics.model.PrCounts;
import com.fleet.analytics.metrics.model.RuleEvidence;
import com.fleet.analytics.metrics.rules.BudgetRiskRule;
import com.fleet.analytics.security.FindingIdGenerator;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Converts internal findings into the approved, role-safe public shape.
 *
 * <p><b>Built positively, never redacted afterwards.</b> Each response object is constructed with
 * exactly the fields the caller's role permits. The alternative — serialize the internal object and
 * delete the domain — fails the moment a new field, a nested copy or a log line carries it, and
 * fails silently. Here a VIEWER's evidence is a different object that never held the domain at all.
 *
 * <p>Everything else is identical between roles: the identifier, every count, severity, ranking,
 * scope and navigation. AC-06.9 makes that explicit — a VIEWER sees the same finding, minus one
 * field.
 */
@Component
public class FindingPresenter {

    /** ADMIN is the only role permitted to see a denied domain (research 9, AC-06.9). */
    private static final String ADMIN = "ADMIN";

    private final FindingIdGenerator idGenerator;
    private final FindingLinkBuilder linkBuilder;

    public FindingPresenter(FindingIdGenerator idGenerator, FindingLinkBuilder linkBuilder) {
        this.idGenerator = idGenerator;
        this.linkBuilder = linkBuilder;
    }

    public AttentionResponse present(AttentionResult result, UUID organisationId, String role,
            DashboardSelection selection) {
        List<FindingResponse> findings = result.findings().stream()
                .map(finding -> present(finding, organisationId, role, selection))
                .toList();
        List<EvaluationLimitResponse> limits = result.limits().stream()
                .map(FindingPresenter::present)
                .toList();
        return new AttentionResponse(findings, result.evaluationsCompleted(), limits);
    }

    private FindingResponse present(FindingCandidate finding, UUID organisationId, String role,
            DashboardSelection selection) {
        return new FindingResponse(
                idGenerator.publicId(organisationId, finding.identity()),
                finding.ruleType().wireName(),
                finding.severity().wireName(),
                finding.scope().scopeType().wireName(),
                finding.scope().scopeId(),
                finding.scope().scopeName(),
                evidence(finding.evidence(), role),
                DisplayResponse.from(finding.displayMagnitude()),
                new FindingResponse.EvaluationPeriodResponse(
                        finding.evaluationFrom(), finding.evaluationTo()),
                linkBuilder.build(finding, selection));
    }

    /** Exhaustive over the sealed evidence hierarchy, so a new rule cannot ship without its shape. */
    private FindingEvidenceResponse evidence(RuleEvidence evidence, String role) {
        return switch (evidence) {
            case RuleEvidence.Budget budget -> budgetEvidence(budget.sample());
            case RuleEvidence.FailureSpike spike -> failureEvidence(spike);
            case RuleEvidence.MergeDecline decline -> declineEvidence(decline);
            case RuleEvidence.Friction friction -> frictionEvidence(friction, role);
        };
    }

    private FindingEvidenceResponse budgetEvidence(BudgetSample sample) {
        return new FindingEvidenceResponse(null, null, null, null, null,
                null, null, null, null, null,
                // Through the shared guard, not longValueExact(): that would accept anything up to
                // Long.MAX_VALUE and hand a client a cent total it silently rounds on parse.
                JsonSafeInteger.of(sample.budgetCents()),
                JsonSafeInteger.of(sample.mtdSpendCents()),
                sample.elapsedDays(),
                sample.daysInMonth(),
                DisplayResponse.from(BudgetRiskRule.forecastDollars(sample)),
                DisplayResponse.from(overrunDisplay(sample)));
    }

    /** The overrun as a percentage, rounded once from the exact integers behind it. */
    private DisplayValue overrunDisplay(BudgetSample sample) {
        BigInteger budgetOverElapsed =
                sample.budgetCents().multiply(BigInteger.valueOf(sample.elapsedDays()));
        BigInteger overrun = sample.mtdSpendCents()
                .multiply(BigInteger.valueOf(sample.daysInMonth()))
                .subtract(budgetOverElapsed);
        return DisplayValue.of(
                MetricCalculator.percentage(overrun, budgetOverElapsed), DisplayUnit.PERCENT);
    }

    private FindingEvidenceResponse failureEvidence(RuleEvidence.FailureSpike spike) {
        return new FindingEvidenceResponse(null, null, null, null, null,
                DisplayResponse.from(rate(spike.current().failed(), spike.current().terminal())),
                DisplayResponse.from(rate(spike.baseline().failed(), spike.baseline().terminal())),
                DisplayResponse.from(points(spike.thresholdPoints())),
                JsonSafeInteger.of(spike.current().failed()),
                new FindingEvidenceResponse.FailureReasonGroupsResponse(
                        spike.reasons().agent(), spike.reasons().platform(), spike.reasons().policy()),
                null, null, null, null, null, null);
    }

    private FindingEvidenceResponse declineEvidence(RuleEvidence.MergeDecline decline) {
        PrCounts current = decline.current();
        PrCounts baseline = decline.baseline();
        return new FindingEvidenceResponse(null, null, null, null, null,
                DisplayResponse.from(rate(current.merged(), current.terminal())),
                DisplayResponse.from(rate(baseline.merged(), baseline.terminal())),
                DisplayResponse.from(points(decline.thresholdPoints())),
                null, null, null, null, null, null, null, null);
    }

    /**
     * The one place a role changes the output. A VIEWER's object is built without the domain rather
     * than with it removed, so no later copy or log line can reintroduce it. Counts are identical.
     */
    private FindingEvidenceResponse frictionEvidence(RuleEvidence.Friction friction, String role) {
        String domain = ADMIN.equals(role) ? friction.counts().normalisedDomain() : null;
        return new FindingEvidenceResponse(domain,
                JsonSafeInteger.of(friction.counts().distinctTasks()),
                JsonSafeInteger.of(friction.counts().distinctUsers()),
                friction.taskThreshold(), friction.userThreshold(),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static DisplayValue rate(long numerator, long denominator) {
        return denominator == 0
                ? null
                : DisplayValue.of(MetricCalculator.percentage(
                        BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)),
                        DisplayUnit.PERCENT);
    }

    private static DisplayValue points(int thresholdPoints) {
        return new DisplayValue(thresholdPoints + ".0", DisplayUnit.PERCENTAGE_POINTS);
    }

    private static EvaluationLimitResponse present(EvaluationLimit limit) {
        return new EvaluationLimitResponse(
                limit.ruleType().wireName(),
                limit.scope().scopeType().wireName(),
                limit.scope().scopeId(),
                limit.scope().scopeName(),
                limit.state().wireName(),
                limit.explanation().code(),
                limit.explanation().text());
    }
}
