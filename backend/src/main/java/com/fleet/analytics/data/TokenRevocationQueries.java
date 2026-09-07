package com.fleet.analytics.data;

import static com.fleet.analytics.data.jooq.tables.RevokedToken.REVOKED_TOKEN;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Shared by API instances; deliberately excluded from the deterministic business dataset. */
@Repository
public class TokenRevocationQueries {
    private final DSLContext db;

    public TokenRevocationQueries(DSLContext db) {
        this.db = db;
    }

    public boolean contains(UUID tokenId) {
        return db.fetchExists(db.selectOne().from(REVOKED_TOKEN)
                .where(REVOKED_TOKEN.TOKEN_ID.eq(tokenId)));
    }

    @Transactional
    public void revoke(UUID tokenId, OffsetDateTime retainUntil, OffsetDateTime now) {
        // Strictly before: a token can still be accepted at the exact clock-skew boundary.
        db.deleteFrom(REVOKED_TOKEN).where(REVOKED_TOKEN.RETAIN_UNTIL.lt(now)).execute();
        db.insertInto(REVOKED_TOKEN)
                .set(REVOKED_TOKEN.TOKEN_ID, tokenId)
                .set(REVOKED_TOKEN.RETAIN_UNTIL, retainUntil)
                .onConflictDoNothing().execute();
    }
}
