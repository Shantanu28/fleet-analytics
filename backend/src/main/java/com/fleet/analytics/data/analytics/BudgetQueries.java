package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.jooq.tables.Budget.BUDGET;

import com.fleet.analytics.metrics.model.BudgetConfiguration;
import java.math.BigInteger;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Configured budgets for one calendar month (contract 6.1).
 *
 * <p>One read for every scope rather than one per evaluated team: budget risk is evaluated over the
 * organisation and every team, and a query per scope would turn a small panel into a query storm on
 * a realistic tenant.
 *
 * <p>A row with a null {@code team_id} is the organisation's own budget. Non-positive amounts are
 * returned rather than filtered out — contract 6.1 reports them as
 * {@code invalid_budget_configuration}, which needs the row to exist and be readable.
 */
@Repository
public class BudgetQueries {

    private final DSLContext db;

    public BudgetQueries(DSLContext db) {
        this.db = db;
    }

    public BudgetConfiguration forMonth(UUID organisationId, YearMonth month) {
        Map<UUID, BigInteger> byTeam = new HashMap<>();
        BigInteger[] organisation = {null};

        db.select(BUDGET.TEAM_ID, BUDGET.AMOUNT_CENTS)
                .from(BUDGET)
                .where(BUDGET.ORG_ID.eq(organisationId))
                .and(BUDGET.PERIOD_MONTH.eq(month.atDay(1)))
                .fetch()
                .forEach(record -> {
                    BigInteger cents = BigInteger.valueOf(record.value2());
                    if (record.value1() == null) {
                        organisation[0] = cents;
                    } else {
                        byTeam.put(record.value1(), cents);
                    }
                });

        return new BudgetConfiguration(organisation[0], byTeam);
    }
}
