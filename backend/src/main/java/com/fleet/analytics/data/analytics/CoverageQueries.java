package com.fleet.analytics.data.analytics;

import static com.fleet.analytics.data.jooq.tables.SourceDayCoverage.SOURCE_DAY_COVERAGE;

import com.fleet.analytics.metrics.model.LogicalSource;
import com.fleet.analytics.metrics.model.SourceDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * Reads per-day source completeness for the interval the request's windows span.
 *
 * <p>One bounded read covers every window rather than one query per window: the previous period,
 * the 28-day baseline, the budget month and the funnel observation all fall inside a single
 * interval, and resolving them in Java from one result keeps the windows independent without
 * paying for five round trips.
 *
 * <p>Rows for a source this build does not recognise are dropped rather than failing the request —
 * an unknown source name cannot make a known metric wrong, and the days it would have covered are
 * simply not counted as covering anything.
 */
@Repository
public class CoverageQueries {

    private final DSLContext db;

    public CoverageQueries(DSLContext db) {
        this.db = db;
    }

    /** @param toInclusive the last day any window touches; both bounds are UTC days. */
    public List<SourceDay> sourceDays(
            UUID organisationId, LocalDate fromInclusive, LocalDate toInclusive) {
        return db.select(SOURCE_DAY_COVERAGE.LOGICAL_SOURCE, SOURCE_DAY_COVERAGE.DAY,
                        SOURCE_DAY_COVERAGE.IS_COMPLETE)
                .from(SOURCE_DAY_COVERAGE)
                .where(SOURCE_DAY_COVERAGE.ORG_ID.eq(organisationId))
                .and(SOURCE_DAY_COVERAGE.DAY.greaterOrEqual(fromInclusive))
                .and(SOURCE_DAY_COVERAGE.DAY.lessOrEqual(toInclusive))
                .fetch()
                .map(record -> {
                    LogicalSource source = LogicalSource.fromWireName(record.value1());
                    return source == null
                            ? null
                            : new SourceDay(source, record.value2(), Boolean.TRUE.equals(record.value3()));
                })
                .stream().filter(Objects::nonNull).toList();
    }
}
