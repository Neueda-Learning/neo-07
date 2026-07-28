package com.neobank.module.support;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repoints the seeded {@code core_config.core_base_url} at a test's own bound port.
 *
 * <p>{@code CoreConfig} has no setter for this column — it is insert-only everywhere in the app —
 * so a raw update is the simplest way for a {@code webEnvironment = RANDOM_PORT} test to make
 * UC-02's engine call its own in-process mock core, rather than accidentally reaching whatever
 * happens to already be listening on the seeded {@code localhost:8080} on the machine running the
 * test (the bug this class exists to make impossible to reintroduce). Works against both H2
 * (unit/slice tests) and MySQL (Testcontainers {@code *IT} tests) — MySQL rejects updating a
 * table while selecting from that same table in a plain subquery ("You can't specify target
 * table ... for update in FROM clause"), so the inner {@code SELECT} is wrapped in a derived
 * table, which both dialects accept.
 */
public final class CoreConfigTestSupport {

    private CoreConfigTestSupport() {
    }

    public static void pointCoreBaseUrlAt(DataSource dataSource, int port) {
        new JdbcTemplate(dataSource).update(
                "UPDATE core_config SET core_base_url = ? "
                        + "WHERE version = (SELECT max_version FROM "
                        + "(SELECT MAX(version) AS max_version FROM core_config) AS current)",
                "http://localhost:" + port);
    }
}
