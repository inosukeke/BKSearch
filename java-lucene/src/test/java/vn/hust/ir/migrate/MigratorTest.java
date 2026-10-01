package vn.hust.ir.migrate;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm thử helper của Migrator (S0.5): _id ổn định (idempotent) + parse thời gian linh hoạt. */
class MigratorTest {

    @Test
    void sha256_isStableForSameUrl() {
        String a = Migrator.sha256("https://hust.edu.vn/vi/");
        String b = Migrator.sha256("https://hust.edu.vn/vi/");
        assertEquals(a, b, "Cùng url phải cho cùng _id → upsert không nhân đôi");
        assertEquals(64, a.length(), "SHA-256 hex = 64 ký tự");
    }

    @Test
    void sha256_differsForDifferentUrl() {
        assertNotEquals(Migrator.sha256("https://a"), Migrator.sha256("https://b"));
    }

    @Test
    void parseInstant_handlesIsoNanoAndDateAndNull() {
        assertEquals(Instant.parse("2026-09-12T08:21:26.539257100Z"),
                Migrator.parseInstant("2026-09-12T08:21:26.539257100Z"));
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"),
                Migrator.parseInstant("2026-03-01"));
        assertNull(Migrator.parseInstant(null));
        assertNull(Migrator.parseInstant(""));
        assertNull(Migrator.parseInstant("không-phải-ngày"));
    }
}
