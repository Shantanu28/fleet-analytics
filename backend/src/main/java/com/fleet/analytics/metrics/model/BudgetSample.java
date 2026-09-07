package com.fleet.analytics.metrics.model;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Everything budget risk is evaluated from, as exact integers (contract 6.1).
 *
 * <p>The forecast is {@code mtdSpend / elapsedDays * daysInMonth}, and the overrun is
 * {@code forecast / budget - 1}. Neither is computed here: the rule compares
 * {@code mtdSpend * daysInMonth} against {@code budget * elapsedDays} by cross-multiplication, so a
 * threshold decision never depends on a rounded projection. Rounding happens once, on the values
 * actually displayed.
 *
 * <p>{@code budgetCents} may be zero or negative — that is a configuration state the rule reports as
 * {@code invalid_budget_configuration}, so the row has to be readable rather than rejected here.
 */
public record BudgetSample(
        BigInteger budgetCents, BigInteger mtdSpendCents, int elapsedDays, int daysInMonth) {

    public BudgetSample {
        Objects.requireNonNull(budgetCents, "budgetCents");
        Objects.requireNonNull(mtdSpendCents, "mtdSpendCents");
        if (elapsedDays < 0) {
            throw new IllegalArgumentException("elapsedDays cannot be negative");
        }
        if (daysInMonth < 28 || daysInMonth > 31) {
            throw new IllegalArgumentException("daysInMonth must be a real calendar month length");
        }
        if (elapsedDays > daysInMonth) {
            throw new IllegalArgumentException("elapsedDays cannot exceed the month");
        }
    }

    public boolean hasPositiveBudget() {
        return budgetCents.signum() > 0;
    }
}
