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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V203: built-in lifecycle Workflows gain a description and CREATORS is renamed Creator -> Partner, but only
 * where the row is still exactly as seeded. A private container migrated to V202, rows inserted in the
 * pre-V203 shape, then migrated to latest.
 */
class V203WorkflowDescriptionsMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void renamesAndDescribesSeededRowsAndLeavesCustomisedOnesAlone() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        flyway(url).target("202").load().migrate();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            String project = insertProject(c);
            String seededCreators = insertWorkflow(c, project, "CREATORS", "CREATORS", "Creator", null);
            String seededMarketing = insertWorkflow(c, project, "MARKETING", "MARKETING", "Post", null);
            String customised = insertWorkflow(c, project, "Custom Creators", "CREATORS", "Influencer", null);
            String ownCopy = insertWorkflow(c, project, "ENGINEERING", "ENGINEERING", "Issue", "Our own words.");

            flyway(url).load().migrate();

            assertThat(noun(c, "workflow_definitions", seededCreators)).isEqualTo("Partner");
            assertThat(description(c, "workflow_definitions", seededCreators)).startsWith("Outside creators and partners");
            assertThat(noun(c, "workflow_definition_versions", seededCreators + "-v")).isEqualTo("Partner");
            assertThat(description(c, "workflow_definition_versions", seededCreators + "-v")).startsWith("Outside creators");
            // Internal id is unchanged.
            assertThat(query(c, "SELECT definition ->> 'id' FROM workflow_definitions WHERE id = '" + seededCreators + "'"))
                    .isEqualTo("CREATORS");

            assertThat(noun(c, "workflow_definitions", seededMarketing)).isEqualTo("Post");
            assertThat(description(c, "workflow_definitions", seededMarketing)).startsWith("Social posts");

            assertThat(noun(c, "workflow_definitions", customised)).isEqualTo("Influencer");
            assertThat(description(c, "workflow_definitions", customised)).isNull();

            assertThat(description(c, "workflow_definitions", ownCopy)).isEqualTo("Our own words.");
            assertThat(description(c, "workflow_definition_versions", ownCopy + "-v")).isEqualTo("Our own words.");
        }
        // Replay-safe: running the migrations again changes nothing.
        flyway(url).load().migrate();
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String insertProject(Connection c) throws SQLException {
        String userId = UUID.randomUUID().toString();
        String projectId = UUID.randomUUID().toString();
        try (PreparedStatement p = c.prepareStatement("INSERT INTO users (id, firebase_uid, email) VALUES (?, ?, ?)")) {
            p.setString(1, userId);
            p.setString(2, "uid-" + userId);
            p.setString(3, userId + "@example.com");
            p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO projects (id, name, key, created_by) VALUES (?, 'P', ?, ?)")) {
            p.setString(1, projectId);
            p.setString(2, projectId.substring(0, 8));
            p.setString(3, userId);
            p.executeUpdate();
        }
        return projectId;
    }

    /** Header id is returned; its snapshot id is "&lt;id&gt;-v". */
    private static String insertWorkflow(Connection c, String project, String name, String slug, String noun,
                                         String description) throws SQLException {
        String id = UUID.randomUUID().toString();
        String json = "{\"schemaVersion\":1,\"id\":\"%s\",\"area\":\"MARKETING\",\"version\":1,\"state\":\"PUBLISHED\",\"noun\":\"%s\"%s}"
                .formatted(slug, noun, description == null ? "" : ",\"description\":\"" + description + "\"");
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO workflow_definitions
                    (id, project_id, name, enabled, definition, version, state, area, schema_version, sidebar_enabled)
                VALUES (?, ?, ?, true, ?::jsonb, 1, 'PUBLISHED', 'MARKETING', 1, true)
                """)) {
            p.setString(1, id);
            p.setString(2, project);
            p.setString(3, name);
            p.setString(4, json);
            p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO workflow_definition_versions (id, workflow_definition_id, version, definition, schema_version, published_at)
                VALUES (?, ?, 1, ?::jsonb, 1, NOW())
                """)) {
            p.setString(1, id + "-v");
            p.setString(2, id);
            p.setString(3, json);
            p.executeUpdate();
        }
        return id;
    }

    private static String noun(Connection c, String table, String id) throws SQLException {
        return query(c, "SELECT definition ->> 'noun' FROM " + table + " WHERE id = '" + id + "'");
    }

    private static String description(Connection c, String table, String id) throws SQLException {
        return query(c, "SELECT definition ->> 'description' FROM " + table + " WHERE id = '" + id + "'");
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
