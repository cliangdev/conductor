package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** V186 hides the seeded MARKETING_CHANGES lifecycle from the sidebar. Template migrated to V185. */
class V186HideMarketingChangesFromSidebarTest {

    private static final String TEMPLATE_DB = "v186_template";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    private static final AtomicInteger CASE_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startContainerAndBuildTemplate() throws SQLException {
        POSTGRES.start();
        execOnAdminDatabase("CREATE DATABASE " + TEMPLATE_DB);
        flyway(jdbcUrl(TEMPLATE_DB)).target("185").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void hidesSeededRowAndLeavesLookalikesAndOthersAlone() throws Exception {
        String url = databaseAt185();
        try (Connection c = connect(url)) {
            String seeded = insertWorkflow(c, insertProject(c, "P"), "MARKETING_CHANGES", "MARKETING_CHANGES");
            String lookalike = insertWorkflow(c, insertProject(c, "Q"), "My changes", "MARKETING_CHANGES");
            String posts = insertWorkflow(c, insertProject(c, "R"), "MARKETING", "MARKETING");

            flyway(url).load().migrate();

            assertThat(sidebar(c, seeded)).isEqualTo("false");
            assertThat(sidebar(c, lookalike)).isEqualTo("true");
            assertThat(sidebar(c, posts)).isEqualTo("true");
        }
    }

    @Test
    void isIdempotentWhenReplayedOutOfOrder() throws Exception {
        String url = databaseAt185();
        try (Connection c = connect(url)) {
            String id = insertWorkflow(c, insertProject(c, "Replay"), "MARKETING_CHANGES", "MARKETING_CHANGES");
            flyway(url).load().migrate();
            String stamp = queryForString(c, "SELECT updated_at::text FROM workflow_definitions WHERE id = '" + id + "'");
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM flyway_schema_history WHERE version = '186'");
            }
            flyway(url).outOfOrder(true).load().migrate();

            assertThat(sidebar(c, id)).isEqualTo("false");
            assertThat(queryForString(c, "SELECT updated_at::text FROM workflow_definitions WHERE id = '" + id + "'"))
                    .isEqualTo(stamp);
        }
    }

    private static String sidebar(Connection c, String id) throws SQLException {
        return queryForString(c, "SELECT sidebar_enabled::text FROM workflow_definitions WHERE id = '" + id + "'");
    }

    private static String insertWorkflow(Connection c, String projectId, String name, String slug) throws SQLException {
        String id = UUID.randomUUID().toString();
        String json = "{\"schemaVersion\":1,\"id\":\"%s\",\"area\":\"MARKETING\",\"version\":1,\"state\":\"PUBLISHED\",\"noun\":\"Change\"}"
                .formatted(slug);
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO workflow_definitions
                    (id, project_id, name, enabled, definition, version, state, area, schema_version, sidebar_enabled)
                VALUES (?, ?, ?, true, ?::jsonb, 1, 'PUBLISHED', 'MARKETING', 1, true)
                """)) {
            st.setString(1, id);
            st.setString(2, projectId);
            st.setString(3, name);
            st.setString(4, json);
            st.executeUpdate();
        }
        return id;
    }

    private static String insertProject(Connection connection, String name) throws SQLException {
        String userId = UUID.randomUUID().toString();
        String projectId = UUID.randomUUID().toString();
        try (PreparedStatement user = connection.prepareStatement(
                "INSERT INTO users (id, firebase_uid, email) VALUES (?, ?, ?)")) {
            user.setString(1, userId);
            user.setString(2, "uid-" + userId);
            user.setString(3, userId + "@example.com");
            user.executeUpdate();
        }
        try (PreparedStatement project = connection.prepareStatement(
                "INSERT INTO projects (id, name, key, created_by) VALUES (?, ?, ?, ?)")) {
            project.setString(1, projectId);
            project.setString(2, name);
            project.setString(3, projectId.substring(0, 8));
            project.setString(4, userId);
            project.executeUpdate();
        }
        return projectId;
    }

    private static String databaseAt185() throws SQLException {
        String database = "v186_case_" + CASE_COUNTER.incrementAndGet();
        execOnAdminDatabase("""
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE datname = '%s' AND pid <> pg_backend_pid()
                """.formatted(TEMPLATE_DB));
        execOnAdminDatabase("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE_DB);
        return jdbcUrl(database);
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String queryForString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execOnAdminDatabase(String sql) throws SQLException {
        try (Connection connection = connect(jdbcUrl(POSTGRES.getDatabaseName()));
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
    }
}
