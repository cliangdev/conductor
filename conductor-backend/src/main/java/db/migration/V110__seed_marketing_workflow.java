package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * COND-23: seed the built-in MARKETING lifecycle Workflow as a real DB row per project.
 *
 * <p>The backfill twin of {@code V74__seed_engineering_workflow}: it inserts one
 * {@code workflow_definitions} header (PUBLISHED, sidebar-enabled) plus its v1
 * {@code workflow_definition_versions} snapshot for every <em>existing</em> project that lacks one, so
 * the Post pipeline shows up in the sidebar without anyone re-creating their workspace. New projects
 * are seeded at creation time by {@code WorkflowSeeder.seedMarketing}.
 *
 * <p>Java (not SQL) so the statechart JSON has a single source of truth — the same canonical classpath
 * file {@code WorkflowSeeder} loads — rather than being duplicated as an inlined SQL literal that could
 * drift. Idempotent via {@code WHERE NOT EXISTS} guards, so re-running on a rolling deploy, or running
 * against a database with zero projects, inserts nothing.
 */
public class V110__seed_marketing_workflow extends BaseJavaMigration {

    private static final String MARKETING_RESOURCE = "/schema/examples/marketing.workflow.json";

    private static final String INSERT_HEADERS = """
            INSERT INTO workflow_definitions
                (id, project_id, name, yaml, enabled, definition, version, state, area, schema_version, sidebar_enabled, created_at, updated_at)
            SELECT gen_random_uuid()::text, p.id, 'MARKETING', NULL, true, ?::jsonb, 1, 'PUBLISHED', 'MARKETING', 1, true, NOW(), NOW()
            FROM projects p
            WHERE NOT EXISTS (
                -- Guard on the statechart slug (definition->>'id'), the identity the resolver keys on.
                SELECT 1 FROM workflow_definitions w
                WHERE w.project_id = p.id AND w.definition ->> 'id' = 'MARKETING'
            )
            -- A project may already own an unrelated workflow literally named 'MARKETING' (a YAML
            -- automation carries no definition slug, so the guard above cannot see it). Skipping that
            -- project is the right outcome; failing the whole deploy on its (project_id, name) unique
            -- index is not.
            ON CONFLICT DO NOTHING
            """;

    private static final String INSERT_VERSIONS = """
            INSERT INTO workflow_definition_versions
                (id, workflow_definition_id, version, definition, schema_version, published_at)
            SELECT gen_random_uuid()::text, w.id, 1, w.definition, 1, NOW()
            FROM workflow_definitions w
            WHERE w.definition ->> 'id' = 'MARKETING'
              AND NOT EXISTS (
                SELECT 1 FROM workflow_definition_versions v
                WHERE v.workflow_definition_id = w.id AND v.version = 1
            )
            """;

    @Override
    public void migrate(Context context) throws Exception {
        String marketingJson = readMarketingJson();
        Connection connection = context.getConnection();

        try (PreparedStatement headers = connection.prepareStatement(INSERT_HEADERS)) {
            headers.setString(1, marketingJson);
            headers.executeUpdate();
        }

        try (Statement versions = connection.createStatement()) {
            versions.executeUpdate(INSERT_VERSIONS);
        }
    }

    private String readMarketingJson() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(MARKETING_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + MARKETING_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
