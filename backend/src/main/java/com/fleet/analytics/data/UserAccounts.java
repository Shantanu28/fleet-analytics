package com.fleet.analytics.data;

import static com.fleet.analytics.data.jooq.tables.AppUser.APP_USER;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Account lookup by already-normalised username. */
@Repository
public class UserAccounts {

    private final DSLContext db;

    public UserAccounts(DSLContext db) {
        this.db = db;
    }

    /** One query returns everything sign-in needs, including the display name for the response. */
    public Optional<Account> findByUsername(String normalisedUsername) {
        return db.select(APP_USER.ID, APP_USER.ORG_ID, APP_USER.ROLE, APP_USER.DISPLAY_NAME,
                        APP_USER.PASSWORD_HASH, APP_USER.IS_DEMO_ACCOUNT)
                .from(APP_USER)
                .where(APP_USER.USERNAME.eq(normalisedUsername))
                .fetchOptional()
                .map(r -> new Account(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(),
                        Boolean.TRUE.equals(r.value6())));
    }
}
