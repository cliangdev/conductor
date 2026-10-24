package db.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** V160 re-activates only product-metrics feeds parked on the 31-day range error, and is idempotent. */
class V160ReactivateProductMetricsFeedsTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @BeforeAll
    static void start() {
        POSTGRES.start();
        flyway("158").migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration config(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false);
    }

    private static Flyway flyway(String target) {
        return config(target).load();
    }

    @Test
    void reactivatesOnlyTheParkedRangeFeedsAndIsIdempotent() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO users (id, firebase_uid, email) VALUES ('u1','fb1','u1@x.io')");
            st.execute("INSERT INTO projects (id, name, key, created_by) VALUES ('p1','P','PP','u1')");
            for (String id : new String[]{"c1", "c2", "c3", "c4"}) {
                st.execute("INSERT INTO connection (id, project_id, connector_id, auth_type)"
                        + " VALUES ('" + id + "','p1','product-metrics','API_KEY')");
            }
            String range = "The metrics server answered HTTP 400: the range is at most 31 days, got 46 (use several calls)";
            insertFeed(st, "f-hit", "c1", "product-metrics", "SETUP_REQUIRED", range);
            insertFeed(st, "f-other-error", "c2", "product-metrics", "SETUP_REQUIRED", "The server rejected the API key (HTTP 401).");
            insertFeed(st, "f-other-connector", "c3", "gsc", "SETUP_REQUIRED", range);
            insertFeed(st, "f-active", "c4", "product-metrics", "ACTIVE", null);

            flyway("160").migrate();
            assertThat(state(st, "f-hit")).isEqualTo("ACTIVE|null|null");
            assertThat(state(st, "f-other-error")).startsWith("SETUP_REQUIRED|");
            assertThat(state(st, "f-other-connector")).startsWith("SETUP_REQUIRED|");
            assertThat(state(st, "f-active")).startsWith("ACTIVE|");

            // Re-running the statement changes nothing further.
            st.execute("UPDATE connector_feed SET cursor_state = '{\"lastDay\":\"2026-10-23\"}' WHERE id = 'f-hit'");
            st.execute(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/resources/db/migration/V160__reactivate_product_metrics_feeds_parked_on_range.sql")));
            assertThat(state(st, "f-hit")).isEqualTo("ACTIVE|null|{\"lastDay\":\"2026-10-23\"}");
        }
    }

    private static void insertFeed(Statement st, String id, String conn, String connector, String status, String error)
            throws Exception {
        st.execute("INSERT INTO connector_feed (id, project_id, connection_id, connector_id, ingest_id, status, last_error, cursor_state)"
                + " VALUES ('" + id + "','p1','" + conn + "','" + connector + "','channel_metrics','" + status + "',"
                + (error == null ? "NULL" : "'" + error.replace("'", "''") + "'") + ",'{\"lastDay\":\"2026-10-22\"}')");
    }

    private static String state(Statement st, String id) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT status, last_error, cursor_state FROM connector_feed WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1) + "|" + (rs.getString(2) == null ? "null" : rs.getString(2).substring(0, 5)) + "|" + rs.getString(3);
        }
    }
}
