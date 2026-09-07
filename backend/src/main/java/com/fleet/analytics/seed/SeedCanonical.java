package com.fleet.analytics.seed;

import static com.fleet.analytics.data.jooq.Tables.ORGANISATION;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.Record;
import org.jooq.Table;

/** Length-framed UTF-8, fixed schema field order, sorted rows, UTC instants and integer money. */
final class SeedCanonical {
    private SeedCanonical() {}

    static String checksum(Map<Table<?>, List<Record>> tables) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Table<?> table : DemoDataset.TABLES) {
                digest.update(frame(table.getName()).getBytes(StandardCharsets.UTF_8));
                tables.get(table).stream().map(SeedCanonical::row).sorted()
                        .forEach(row -> digest.update(frame(row).getBytes(StandardCharsets.UTF_8)));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String row(Record record) {
        StringBuilder out = new StringBuilder();
        for (var field : record.fields()) {
            // Installation-time values and dataset_publication are never passed to this method.
            if (field.getName().equals("password_hash")) continue;
            Object value = record.get(field);
            String canonical = value instanceof OffsetDateTime time ? time.toInstant().toString()
                    : value == null ? null : value.toString();
            out.append(frame(field.getName())).append(frame(canonical));
        }
        return out.toString();
    }

    private static String frame(String value) {
        return value == null ? "-1:" : value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    static String counts(Map<Table<?>, List<Record>> tables) {
        Map<String, Map<String, Long>> counts = new TreeMap<>();
        for (Table<?> table : DemoDataset.TABLES) {
            for (Record row : tables.get(table)) {
                String tenant = row.get(table.equals(ORGANISATION) ? "id" : "org_id").toString();
                counts.computeIfAbsent(tenant, ignored -> new TreeMap<>())
                        .merge(table.getName(), 1L, Long::sum);
            }
        }
        StringBuilder json = new StringBuilder("{");
        counts.forEach((tenant, values) -> {
            if (json.length() > 1) json.append(',');
            json.append('"').append(tenant).append("\":{");
            boolean first = true;
            for (Table<?> table : DemoDataset.TABLES) {
                if (!first) json.append(',');
                first = false;
                json.append('"').append(table.getName()).append("\":")
                        .append(values.getOrDefault(table.getName(), 0L));
            }
            json.append('}');
        });
        return json.append('}').toString();
    }
}
