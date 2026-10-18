package com.conductor.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestStatsTest {

    @Test
    void normalizesLiteralsAndWhitespace() {
        String sql = "SELECT *  FROM work_items\n WHERE id = 42 AND name = 'it''s' AND price > 1.5";
        assertThat(RequestStats.normalizeSql(sql))
                .isEqualTo("SELECT * FROM work_items WHERE id = ? AND name = ? AND price > ?");
    }

    @Test
    void collapsesInListsAndKeepsIdentifierDigits() {
        assertThat(RequestStats.normalizeSql("select * from t1 where a in (?, ?, ?, ?)"))
                .isEqualTo("select * from t1 where a in (?)");
        assertThat(RequestStats.normalizeSql("select c2 from t where x in (1, 2, 3)"))
                .isEqualTo("select c2 from t where x in (?)");
    }

    @Test
    void capsLengthAndDistinctStatements() {
        assertThat(RequestStats.normalizeSql("select " + "a, ".repeat(500)).length())
                .isLessThanOrEqualTo(RequestStats.MAX_SQL_LENGTH + 3);
        try (RequestStats.Scope scope = RequestStats.start()) {
            for (int i = 0; i < RequestStats.MAX_DISTINCT_SQL + 50; i++) {
                RequestStats.recordQuery("select * from t_" + "y".repeat(i + 1), 1, 1);
            }
            assertThat(scope.stats().sqlCounts().size()).isLessThanOrEqualTo(RequestStats.MAX_DISTINCT_SQL + 1);
            assertThat(scope.stats().dbQueries()).isEqualTo(RequestStats.MAX_DISTINCT_SQL + 50);
        }
    }

    @Test
    void recordingWithoutAScopeIsANoOp() {
        RequestStats.clear();
        RequestStats.recordQuery("select 1", 5, 1);
        RequestStats.recordConnectionCheckout(5);
        RequestStats.recordGcsCall(5);
        RequestStats.recordSignedUrl(5);
        assertThat(RequestStats.get()).isNull();
    }

    @Test
    void nestedScopesRestoreThePreviousOne() {
        try (RequestStats.Scope outer = RequestStats.start()) {
            RequestStats.recordQuery("select 1", 1, 1);
            try (RequestStats.Scope inner = RequestStats.start()) {
                RequestStats.recordQuery("select 2", 1, 1);
                assertThat(inner.stats().dbQueries()).isEqualTo(1);
            }
            assertThat(RequestStats.get()).isSameAs(outer.stats());
            assertThat(outer.stats().dbQueries()).isEqualTo(1);
        }
        assertThat(RequestStats.get()).isNull();
    }

    @Test
    void reportsRepeatedSqlMostFrequentFirst() {
        try (RequestStats.Scope scope = RequestStats.start()) {
            for (int i = 0; i < 3; i++) RequestStats.recordQuery("select * from a where id = " + i, 1, 1);
            for (int i = 0; i < 5; i++) RequestStats.recordQuery("select * from b where id = " + i, 1, 1);
            RequestStats.recordQuery("select * from c", 1, 1);
            assertThat(scope.stats().topRepeatedSql(5)).extracting(Map.Entry::getKey)
                    .containsExactly("select * from b where id = ?", "select * from a where id = ?");
        }
    }
}
