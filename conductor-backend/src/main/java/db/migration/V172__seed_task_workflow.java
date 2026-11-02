package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Seed the built-in TASK lifecycle Workflow (the lightweight lifecycle of a child Work Item under a PRD or
 * another task) as a real DB row per project.
 *
 * <p>The backfill twin of {@code V157__seed_marketing_changes_workflow}: it inserts one
 * {@code workflow_definitions} header (PUBLISHED) plus its v1 {@code workflow_definition_versions} snapshot
 * for every <em>existing</em> project that lacks one. Unlike the other built-ins the header is seeded with
 * {@code sidebar_enabled = false}: tasks are reached from their parent's Plan tab, never from navigation. New
 * projects are seeded at creation time by {@code WorkflowSeeder.seedTask}.
 *
 * <p>Java (not SQL) so the statechart JSON has a single source of truth — the same canonical classpath
 * file {@code WorkflowSeeder} loads. Idempotent via {@code WHERE NOT EXISTS} guards, so re-running on a
 * rolling deploy, or running against a database with zero projects, inserts nothing.
 */
public class V172__seed_task_workflow extends BaseJavaMigration {

    private static final String DEFINITION_RESOURCE = "/schema/examples/task.workflow.json";

    // One statement: the snapshot is written only for the header rows this run inserted (RETURNING), so a
    // pre-existing definition sharing the slug -- say a user-authored DRAFT -- is never published here.
    // Replays insert no headers and therefore no versions.
    private static final String SEED = """
            WITH inserted AS (
                INSERT INTO workflow_definitions
                    (id, project_id, name, yaml, enabled, definition, version, state, area, schema_version, sidebar_enabled, created_at, updated_at)
                SELECT gen_random_uuid()::text, p.id, 'TASK', NULL, true, ?::jsonb, 1, 'PUBLISHED', 'TASK', 1, false, NOW(), NOW()
                FROM projects p
                WHERE NOT EXISTS (
                    -- Guard on the statechart slug (definition->>'id'), the identity the resolver keys on.
                    SELECT 1 FROM workflow_definitions w
                    WHERE w.project_id = p.id AND w.definition ->> 'id' = 'TASK'
                )
                -- A project may already own an unrelated workflow literally named 'TASK' (a YAML automation
                -- carries no definition slug, so the guard above cannot see it). Skipping that project is the
                -- right outcome; failing the whole deploy on its (project_id, name) unique index is not.
                ON CONFLICT DO NOTHING
                RETURNING id, definition
            )
            INSERT INTO workflow_definition_versions
                (id, workflow_definition_id, version, definition, schema_version, published_at)
            SELECT gen_random_uuid()::text, i.id, 1, i.definition, 1, NOW()
            FROM inserted i
            """;

    @Override
    public void migrate(Context context) throws Exception {
        String definitionJson = readDefinitionJson();
        Connection connection = context.getConnection();
        try (PreparedStatement seed = connection.prepareStatement(SEED)) {
            seed.setString(1, definitionJson);
            seed.executeUpdate();
        }
    }

    private String readDefinitionJson() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(DEFINITION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + DEFINITION_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
