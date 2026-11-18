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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V209: the legacy doc_comments tables and their V205 mirror triggers are dropped, after any legacy row the
 * triggers missed (written since V205, no unified twin) is carried over. A private container migrated to V208,
 * legacy rows inserted in the pre-V209 shape, then migrated to latest.
 */
class V209DropLegacyDocCommentsTest {

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
    void carriesOverLegacyRowsWithoutATwinThenDropsTheLegacyObjects() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        flyway(url).target("208").load().migrate();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            String user = UUID.randomUUID().toString();
            update(c, "INSERT INTO users (id, firebase_uid, email) VALUES ('%s', 'uid-%s', '%s@example.com')"
                    .formatted(user, user, user));
            String project = UUID.randomUUID().toString();
            update(c, "INSERT INTO projects (id, name, key, created_by) VALUES ('%s', 'P', '%s', '%s')"
                    .formatted(project, project.substring(0, 8), user));
            String doc = UUID.randomUUID().toString();
            update(c, "INSERT INTO project_docs (id, project_id, title, created_by, updated_by) VALUES ('%s', '%s', 'D', '%s', '%s')"
                    .formatted(doc, project, user, user));

            // Written by the previous release after V205 and mirrored by the trigger: has a twin already.
            String mirrored = insertLegacyComment(c, doc, user, "mirrored", "now()");
            // Written after V205 while the trigger missed it: no twin, must be carried over.
            update(c, "ALTER TABLE doc_comments DISABLE TRIGGER trg_mirror_doc_comment");
            update(c, "ALTER TABLE doc_comment_replies DISABLE TRIGGER trg_mirror_doc_comment_reply");
            String missed = insertLegacyComment(c, doc, user, "missed", "now()");
            String missedReply = insertLegacyReply(c, missed, user, "missed reply", "now()");
            // Copied by V205, then deleted by the new code: older than V205, no twin, must stay gone.
            String deleted = insertLegacyComment(c, doc, user, "deleted", "now() - interval '1 day'");
            String deletedReply = insertLegacyReply(c, deleted, user, "deleted reply", "now() - interval '1 day'");
            update(c, "ALTER TABLE doc_comments ENABLE TRIGGER trg_mirror_doc_comment");
            update(c, "ALTER TABLE doc_comment_replies ENABLE TRIGGER trg_mirror_doc_comment_reply");
            assertThat(count(c, "comments", mirrored)).isEqualTo(1);
            assertThat(count(c, "comments", missed)).isZero();

            flyway(url).load().migrate();

            assertThat(count(c, "comments", mirrored)).isEqualTo(1);
            assertThat(count(c, "comments", missed)).isEqualTo(1);
            assertThat(query(c, "SELECT content FROM comments WHERE id = '" + missed + "'")).isEqualTo("missed");
            assertThat(query(c, "SELECT project_doc_id FROM comments WHERE id = '" + missed + "'")).isEqualTo(doc);
            assertThat(query(c, "SELECT line_number FROM comments WHERE id = '" + missed + "'")).isEqualTo("0");
            assertThat(query(c, "SELECT quoted_text FROM comments WHERE id = '" + missed + "'")).isEqualTo("");
            assertThat(count(c, "comment_replies", missedReply)).isEqualTo(1);
            assertThat(count(c, "comments", deleted)).isZero();
            assertThat(count(c, "comment_replies", deletedReply)).isZero();

            assertThat(query(c, "SELECT to_regclass('doc_comments')")).isNull();
            assertThat(query(c, "SELECT to_regclass('doc_comment_replies')")).isNull();
            assertThat(query(c, "SELECT count(*) FROM pg_proc WHERE proname IN ('mirror_doc_comment', 'mirror_doc_comment_reply')"))
                    .isEqualTo("0");
        }
        // Replay-safe: running the migrations again changes nothing.
        flyway(url).load().migrate();
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String insertLegacyComment(Connection c, String doc, String user, String content, String createdAt)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        update(c, ("INSERT INTO doc_comments (id, doc_id, author_id, content, created_at, updated_at) "
                + "VALUES ('%s', '%s', '%s', '%s', %s, %s)").formatted(id, doc, user, content, createdAt, createdAt));
        return id;
    }

    private static String insertLegacyReply(Connection c, String comment, String user, String content, String createdAt)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        update(c, ("INSERT INTO doc_comment_replies (id, comment_id, author_id, content, created_at) "
                + "VALUES ('%s', '%s', '%s', '%s', %s)").formatted(id, comment, user, content, createdAt));
        return id;
    }

    private static int count(Connection c, String table, String id) throws SQLException {
        return Integer.parseInt(query(c, "SELECT count(*) FROM " + table + " WHERE id = '" + id + "'"));
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.executeUpdate();
        }
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
