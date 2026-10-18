package com.conductor.observability;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class QueryStatsDataSourcePostProcessorTest {

    @Test
    void countsStatementsAndCheckoutsAndStaysUnwrappable() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:obs;DB_CLOSE_DELAY=-1", "sa", "");
        DataSource ds = (DataSource) new QueryStatsDataSourcePostProcessor().postProcessAfterInitialization(h2, "dataSource");

        try (RequestStats.Scope scope = RequestStats.start()) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("create table if not exists t (id int)");
                s.execute("insert into t values (1)");
                s.executeQuery("select * from t where id = 1").close();
            }
            RequestStats stats = scope.stats();
            assertThat(stats.connCheckouts()).isEqualTo(1);
            assertThat(stats.dbQueries()).isEqualTo(3);
            assertThat(stats.sqlCounts()).containsKey("select * from t where id = ?");
        }
        assertThat(ds.isWrapperFor(DriverManagerDataSource.class)).isTrue();
    }

    @Test
    void doesNotCountOutsideAScope() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:obs2;DB_CLOSE_DELAY=-1", "sa", "");
        DataSource ds = (DataSource) new QueryStatsDataSourcePostProcessor().postProcessAfterInitialization(h2, "dataSource");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("select 1");
        }
        assertThat(RequestStats.get()).isNull();
    }
}
