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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the V110 backfill that seeds MARKETING into projects that already existed when it shipped.
 *
 * <p>Uses a private container rather than {@code AbstractPostgresIntegrationTest}: the assertions are
 * about what a Flyway run does to a whole database, which the shared (already fully migrated, already
 * populated) test database cannot express. No Spring context is built here, so this costs one container
 * and nothing from the context cache. Each case runs against its own database cloned from a template
 * migrated to just before V110, so the full history is replayed once, not once per test.
 */
class V110SeedMarketingWorkflowTest {

    private static final String TEMPLATE_DB = "v110_template";
    private static final String VERSION_BEFORE_V110 = "109";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    private static final AtomicInteger CASE_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startContainerAndBuildTemplate() throws SQLException {
        POSTGRES.start();
        execOnAdminDatabase("CREATE DATABASE " + TEMPLATE_DB);
        flyway(jdbcUrl(TEMPLATE_DB)).target(VERSION_BEFORE_V110).load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void seedsMarketingIntoEveryExistingProject() throws Exception {
        String url = databaseMigratedToJustBeforeV110();
        try (Connection connection = connect(url)) {
            String projectA = insertProject(connection, "Alpha");
            String projectB = insertProject(connection, "Beta");

            migrateToLatest(url);

            assertThat(marketingDefinitionProjectIds(connection)).containsExactlyInAnyOrder(projectA, projectB);
            assertMarketingHeaderIsPublishedAndSidebarEnabled(connection, projectA);
            assertMarketingHeaderIsPublishedAndSidebarEnabled(connection, projectB);
            assertThat(marketingVersionSnapshotCount(connection)).isEqualTo(2);
        }
    }

    @Test
    void leavesAnAlreadySeededProjectUntouchedAndSeedsOnlyTheMissingOne() throws Exception {
        String url = databaseMigratedToJustBeforeV110();
        try (Connection connection = connect(url)) {
            String seededProject = insertProject(connection, "Already Seeded");
            String unseededProject = insertProject(connection, "Needs Marketing");
            String existingDefinitionId = insertMarketingDefinition(connection, seededProject, "Custom Marketing");

            migrateToLatest(url);

            assertThat(marketingDefinitionProjectIds(connection))
                    .containsExactlyInAnyOrder(seededProject, unseededProject);
            assertThat(queryForString(connection,
                    "SELECT name FROM workflow_definitions WHERE id = '" + existingDefinitionId + "'"))
                    .isEqualTo("Custom Marketing");
            assertThat(queryForString(connection,
                    "SELECT definition ->> 'noun' FROM workflow_definitions WHERE id = '" + existingDefinitionId + "'"))
                    .isEqualTo("Announcement");
        }
    }

    @Test
    void appliesCleanlyWhenNoProjectsExist() throws Exception {
        String url = databaseMigratedToJustBeforeV110();

        migrateToLatest(url);

        try (Connection connection = connect(url)) {
            assertThat(marketingDefinitionProjectIds(connection)).isEmpty();
            assertThat(queryForString(connection,
                    "SELECT success::text FROM flyway_schema_history WHERE version = '110'")).isEqualTo("true");
        }
    }

    @Test
    void skipsAProjectWhoseWorkflowNameIsAlreadyTaken() throws Exception {
        String url = databaseMigratedToJustBeforeV110();
        try (Connection connection = connect(url)) {
            String projectWithNameClash = insertProject(connection, "Name Clash");
            String otherProject = insertProject(connection, "Other");
            insertYamlAutomation(connection, projectWithNameClash, "MARKETING");

            migrateToLatest(url);

            assertThat(marketingDefinitionProjectIds(connection)).containsExactly(otherProject);
        }
    }

    private static void assertMarketingHeaderIsPublishedAndSidebarEnabled(Connection connection, String projectId)
            throws SQLException {
        String row = queryForString(connection, """
                SELECT w.state || '|' || w.area || '|' || w.sidebar_enabled || '|' || w.version || '|'
                       || w.schema_version || '|' || w.enabled || '|' || (w.definition ->> 'noun')
                       || '|' || COALESCE((SELECT v.version::text FROM workflow_definition_versions v
                                           WHERE v.workflow_definition_id = w.id), 'none')
                FROM workflow_definitions w
                WHERE w.project_id = '%s' AND w.definition ->> 'id' = 'MARKETING'
                """.formatted(projectId));

        assertThat(row).isEqualTo("PUBLISHED|MARKETING|true|1|1|true|Post|1");
    }

    private static List<String> marketingDefinitionProjectIds(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT project_id FROM workflow_definitions WHERE definition ->> 'id' = 'MARKETING'")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static int marketingVersionSnapshotCount(Connection connection) throws SQLException {
        return Integer.parseInt(queryForString(connection, """
                SELECT COUNT(*)::text FROM workflow_definition_versions v
                JOIN workflow_definitions w ON w.id = v.workflow_definition_id
                WHERE w.definition ->> 'id' = 'MARKETING' AND v.version = 1
                  AND v.definition ->> 'id' = 'MARKETING'
                """));
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

    private static String insertMarketingDefinition(Connection connection, String projectId, String name)
            throws SQLException {
        String definitionId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (id, project_id, name, enabled, definition, version, state, area, schema_version, sidebar_enabled)
                VALUES (?, ?, ?, true, ?::jsonb, 1, 'PUBLISHED', 'MARKETING', 1, true)
                """)) {
            statement.setString(1, definitionId);
            statement.setString(2, projectId);
            statement.setString(3, name);
            statement.setString(4, """
                    {"schemaVersion":1,"id":"MARKETING","area":"MARKETING","version":1,"state":"PUBLISHED",
                     "noun":"Announcement","types":["POST"],
                     "statuses":[{"id":"DRAFT","label":"Draft","category":"open","initial":true}],
                     "transitions":[]}
                    """);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_definition_versions
                    (id, workflow_definition_id, version, definition, schema_version)
                SELECT ?, id, 1, definition, 1 FROM workflow_definitions WHERE id = ?
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, definitionId);
            statement.executeUpdate();
        }
        return definitionId;
    }

    private static void insertYamlAutomation(Connection connection, String projectId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workflow_definitions (id, project_id, name, yaml, enabled) VALUES (?, ?, ?, 'on: push', true)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, projectId);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    private static String databaseMigratedToJustBeforeV110() throws SQLException {
        String database = "v110_case_" + CASE_COUNTER.incrementAndGet();
        execOnAdminDatabase("""
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE datname = '%s' AND pid <> pg_backend_pid()
                """.formatted(TEMPLATE_DB));
        execOnAdminDatabase("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE_DB);
        return jdbcUrl(database);
    }

    private static void migrateToLatest(String url) {
        flyway(url).load().migrate();
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
