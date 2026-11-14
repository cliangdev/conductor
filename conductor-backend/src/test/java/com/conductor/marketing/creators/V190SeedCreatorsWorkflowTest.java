package com.conductor.marketing.creators;

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
 * The V190 backfill that seeds the CREATORS lifecycle into projects that already existed. Same shape as
 * {@code V157SeedMarketingChangesWorkflowTest}: a private container, one template database migrated to just
 * before V190, one clone per case.
 */
class V190SeedCreatorsWorkflowTest {

    private static final String TEMPLATE_DB = "v190_template";
    // the newest migration below 190 on this branch; V188/V189 (reserved for other tasks) are not required
    private static final String VERSION_BEFORE_V190 = "187";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
    private static final AtomicInteger CASE_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startContainerAndBuildTemplate() throws SQLException {
        POSTGRES.start();
        execOnAdminDatabase("CREATE DATABASE " + TEMPLATE_DB);
        flyway(jdbcUrl(TEMPLATE_DB)).target(VERSION_BEFORE_V190).load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void seedsCreatorsIntoEveryExistingProjectAsAPublishedMarketingWorkflow() throws Exception {
        String url = databaseMigratedToJustBeforeV190();
        try (Connection connection = connect(url)) {
            String projectA = insertProject(connection, "Alpha");
            String projectB = insertProject(connection, "Beta");

            migrateToLatest(url);

            assertThat(projectIdsWithDefinition(connection)).containsExactlyInAnyOrder(projectA, projectB);
            assertThat(queryForString(connection, """
                    SELECT w.state || '|' || w.area || '|' || w.sidebar_enabled || '|' || (w.definition ->> 'noun')
                           || '|' || jsonb_array_length(w.definition -> 'fields')
                    FROM workflow_definitions w
                    WHERE w.project_id = '%s' AND w.definition ->> 'id' = 'CREATORS'
                    """.formatted(projectA))).isEqualTo("PUBLISHED|MARKETING|true|Partner|7");
            assertThat(queryForString(connection, """
                    SELECT COUNT(*)::text FROM workflow_definition_versions v
                    JOIN workflow_definitions w ON w.id = v.workflow_definition_id
                    WHERE w.definition ->> 'id' = 'CREATORS' AND v.version = 1
                    """)).isEqualTo("2");
        }
    }

    @Test
    void skipsAProjectThatAlreadyHasCreatorsAndLeavesItUntouched() throws Exception {
        String url = databaseMigratedToJustBeforeV190();
        try (Connection connection = connect(url)) {
            String seededProject = insertProject(connection, "Already Seeded");
            String unseededProject = insertProject(connection, "Needs Creators");
            String existingId = insertExistingDefinition(connection, seededProject);

            migrateToLatest(url);

            assertThat(projectIdsWithDefinition(connection)).containsExactlyInAnyOrder(seededProject, unseededProject);
            assertThat(queryForString(connection,
                    "SELECT definition ->> 'noun' FROM workflow_definitions WHERE id = '" + existingId + "'"))
                    .isEqualTo("Influencer");
            // A pre-existing (e.g. user-authored draft) definition must never be published by the seed.
            assertThat(queryForString(connection,
                    "SELECT COUNT(*)::text FROM workflow_definition_versions WHERE workflow_definition_id = '"
                            + existingId + "'")).isEqualTo("0");
        }
    }

    @Test
    void isIdempotentWhenTheMigrationReplays() throws Exception {
        String url = databaseMigratedToJustBeforeV190();
        try (Connection connection = connect(url)) {
            String project = insertProject(connection, "Replayed");

            migrateToLatest(url);
            // Forget V190 ran and migrate again: a replay (a rolling deploy racing itself) must add nothing.
            try (Statement forget = connection.createStatement()) {
                forget.execute("DELETE FROM flyway_schema_history WHERE version = '190'");
            }
            // later migrations are already applied, so the replayed one is out of order
            flyway(url).outOfOrder(true).load().migrate();

            assertThat(projectIdsWithDefinition(connection)).containsExactly(project);
            assertThat(queryForString(connection,
                    "SELECT COUNT(*)::text FROM workflow_definition_versions v "
                            + "JOIN workflow_definitions w ON w.id = v.workflow_definition_id "
                            + "WHERE w.definition ->> 'id' = 'CREATORS'")).isEqualTo("1");
        }
    }

    @Test
    void appliesCleanlyWhenNoProjectsExist() throws Exception {
        String url = databaseMigratedToJustBeforeV190();

        migrateToLatest(url);

        try (Connection connection = connect(url)) {
            assertThat(projectIdsWithDefinition(connection)).isEmpty();
            assertThat(queryForString(connection,
                    "SELECT success::text FROM flyway_schema_history WHERE version = '190'")).isEqualTo("true");
        }
    }

    private static List<String> projectIdsWithDefinition(Connection connection) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT project_id FROM workflow_definitions WHERE definition ->> 'id' = 'CREATORS'")) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
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

    private static String insertExistingDefinition(Connection connection, String projectId) throws SQLException {
        String definitionId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_definitions
                    (id, project_id, name, enabled, definition, version, state, area, schema_version, sidebar_enabled)
                VALUES (?, ?, 'Custom Creators', true, ?::jsonb, 1, 'PUBLISHED', 'MARKETING', 1, true)
                """)) {
            statement.setString(1, definitionId);
            statement.setString(2, projectId);
            statement.setString(3, """
                    {"schemaVersion":1,"id":"CREATORS","area":"MARKETING","version":1,
                     "state":"PUBLISHED","noun":"Influencer","types":["OTHER"],
                     "statuses":[{"id":"PROSPECT","label":"Prospect","category":"open","initial":true}],
                     "transitions":[]}
                    """);
            statement.executeUpdate();
        }
        return definitionId;
    }

    private static String databaseMigratedToJustBeforeV190() throws SQLException {
        String database = "v190_case_" + CASE_COUNTER.incrementAndGet();
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
