package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * COND-22: seed the built-in ENGINEERING lifecycle Workflow as a real DB row per project.
 *
 * <p>Before this, ENGINEERING existed only as a classpath built-in resolved lazily by
 * {@code WorkflowDefinitionResolver}, so it never appeared in {@code GET /workflows} and the sidebar
 * could not go dynamic. This inserts one {@code workflow_definitions} header (PUBLISHED, sidebar-enabled)
 * plus its v1 {@code workflow_definition_versions} snapshot for every project that lacks one.
 *
 * <p>Java (not SQL) so the statechart JSON has a single source of truth — the canonical classpath file
 * — rather than being duplicated as an inlined SQL literal that could drift. Idempotent via
 * {@code WHERE NOT EXISTS} guards, so re-running on a rolling deploy inserts nothing.
 * New projects are seeded separately by {@code WorkflowSeeder} in {@code ProjectService.createWorkspace}.
 */
public class V74__seed_engineering_workflow extends BaseJavaMigration {

    private static final String ENGINEERING_RESOURCE = "/schema/examples/engineering.workflow.json";

    private static final String INSERT_HEADERS = """
            INSERT INTO workflow_definitions
                (id, project_id, name, yaml, enabled, definition, version, state, area, schema_version, sidebar_enabled, created_at, updated_at)
            SELECT gen_random_uuid()::text, p.id, 'ENGINEERING', NULL, true, ?::jsonb, 1, 'PUBLISHED', 'ENGINEERING', 1, true, NOW(), NOW()
            FROM projects p
            WHERE NOT EXISTS (
                -- Guard on the statechart slug (definition->>'id'), the identity the resolver keys on.
                SELECT 1 FROM workflow_definitions w
                WHERE w.project_id = p.id AND w.definition ->> 'id' = 'ENGINEERING'
            )
            """;

    private static final String INSERT_VERSIONS = """
            INSERT INTO workflow_definition_versions
                (id, workflow_definition_id, version, definition, schema_version, published_at)
            SELECT gen_random_uuid()::text, w.id, 1, w.definition, 1, NOW()
            FROM workflow_definitions w
            WHERE w.definition ->> 'id' = 'ENGINEERING'
              AND NOT EXISTS (
                SELECT 1 FROM workflow_definition_versions v
                WHERE v.workflow_definition_id = w.id AND v.version = 1
            )
            """;

    @Override
    public void migrate(Context context) throws Exception {
        String engineeringJson = readEngineeringJson();
        Connection connection = context.getConnection();

        try (PreparedStatement headers = connection.prepareStatement(INSERT_HEADERS)) {
            headers.setString(1, engineeringJson);
            headers.executeUpdate();
        }

        try (Statement versions = connection.createStatement()) {
            versions.executeUpdate(INSERT_VERSIONS);
        }
    }

    private String readEngineeringJson() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(ENGINEERING_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + ENGINEERING_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
