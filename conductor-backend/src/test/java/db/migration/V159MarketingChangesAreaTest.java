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

/**
 * V159 moves the built-in Marketing changes lifecycle from the area V157 seeded ('MARKETING_CHANGES') into
 * 'MARKETING'. Template database migrated to V158, one clone per case; V157 has already seeded a row per
 * project, so cases rewrite those rows to the states they need before running V159.
 */
class V159MarketingChangesAreaTest {

    private static final String TEMPLATE_DB = "v159_template";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    private static final AtomicInteger CASE_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startContainerAndBuildTemplate() throws SQLException {
        POSTGRES.start();
        execOnAdminDatabase("CREATE DATABASE " + TEMPLATE_DB);
        flyway(jdbcUrl(TEMPLATE_DB)).target("158").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void movesSeededHeaderAndSnapshotIntoMarketingAndLeavesOthersAlone() throws Exception {
        String url = databaseAt158();
        try (Connection c = connect(url)) {
            String seeded = insertProject(c, "Seeded");
            String edited = insertProject(c, "Edited");
            String posts = insertProject(c, "Posts");
            String seededId = insertChanges(c, seeded, "MARKETING_CHANGES");
            String editedId = insertChanges(c, edited, "GROWTH");
            String postsId = insertWorkflow(c, posts, "MARKETING", "MARKETING", "Post");

            flyway(url).load().migrate();

            assertThat(row(c, seededId)).isEqualTo("MARKETING|MARKETING|MARKETING");
            assertThat(queryForString(c, "SELECT definition ->> 'noun' FROM workflow_definitions WHERE id = '" + seededId + "'"))
                    .isEqualTo("Change");
            // A user-changed area is not clobbered, in the header or its snapshot.
            assertThat(row(c, editedId)).isEqualTo("GROWTH|GROWTH|GROWTH");
            // Unrelated workflows are untouched.
            assertThat(row(c, postsId)).isEqualTo("MARKETING|MARKETING|MARKETING");
        }
    }

    @Test
    void isIdempotentWhenReplayedOutOfOrder() throws Exception {
        String url = databaseAt158();
        try (Connection c = connect(url)) {
            String id = insertChanges(c, insertProject(c, "Replay"), "MARKETING_CHANGES");
            flyway(url).load().migrate();
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM flyway_schema_history WHERE version = '159'");
            }
            flyway(url).outOfOrder(true).load().migrate();

            assertThat(row(c, id)).isEqualTo("MARKETING|MARKETING|MARKETING");
        }
    }

    /** V157 on a fresh project seeds under the old area; V159 must then move that real seeded row. */
    @Test
    void movesTheRowV157ActuallySeeded() throws Exception {
        String url = databaseAt158();
        try (Connection c = connect(url)) {
            String project = insertProject(c, "Backfilled");
            // V157 already ran in the template (no projects then); replay it against this project.
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM flyway_schema_history WHERE version = '157'");
            }
            flyway(url).target("158").outOfOrder(true).load().migrate();
            assertThat(queryForString(c, "SELECT area FROM workflow_definitions WHERE project_id = '" + project + "'"))
                    .isEqualTo("MARKETING_CHANGES");

            flyway(url).load().migrate();

            assertThat(queryForString(c, "SELECT area || '|' || (definition ->> 'area') FROM workflow_definitions WHERE project_id = '" + project + "'"))
                    .isEqualTo("MARKETING|MARKETING");
            assertThat(queryForString(c, """
                    SELECT v.definition ->> 'area' FROM workflow_definition_versions v
                    JOIN workflow_definitions w ON w.id = v.workflow_definition_id WHERE w.project_id = '%s'
                    """.formatted(project))).isEqualTo("MARKETING");
        }
    }

    /** header column | header definition area | v1 snapshot area */
    private static String row(Connection c, String id) throws SQLException {
        return queryForString(c, """
                SELECT w.area || '|' || (w.definition ->> 'area') || '|' || (v.definition ->> 'area')
                FROM workflow_definitions w JOIN workflow_definition_versions v ON v.workflow_definition_id = w.id
                WHERE w.id = '%s'
                """.formatted(id));
    }

    private static String insertChanges(Connection c, String projectId, String area) throws SQLException {
        return insertWorkflow(c, projectId, "MARKETING_CHANGES", area, "Change");
    }

    private static String insertWorkflow(Connection c, String projectId, String slug, String area, String noun)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        String json = "{\"schemaVersion\":1,\"id\":\"%s\",\"area\":\"%s\",\"version\":1,\"state\":\"PUBLISHED\",\"noun\":\"%s\"}"
                .formatted(slug, area, noun);
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO workflow_definitions
                    (id, project_id, name, enabled, definition, version, state, area, schema_version, sidebar_enabled)
                VALUES (?, ?, ?, true, ?::jsonb, 1, 'PUBLISHED', ?, 1, true)
                """)) {
            st.setString(1, id);
            st.setString(2, projectId);
            st.setString(3, slug);
            st.setString(4, json);
            st.setString(5, area);
            st.executeUpdate();
        }
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO workflow_definition_versions (id, workflow_definition_id, version, definition, schema_version, published_at)
                VALUES (?, ?, 1, ?::jsonb, 1, NOW())
                """)) {
            st.setString(1, UUID.randomUUID().toString());
            st.setString(2, id);
            st.setString(3, json);
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

    private static String databaseAt158() throws SQLException {
        String database = "v159_case_" + CASE_COUNTER.incrementAndGet();
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
