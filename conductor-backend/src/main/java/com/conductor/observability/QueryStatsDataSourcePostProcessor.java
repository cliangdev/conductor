package com.conductor.observability;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Wraps the application {@link DataSource} so every statement and every connection checkout is timed
 * into the current {@link RequestStats}. Registered by {@link ObservabilityConfig} only when
 * {@code observability.datasource-proxy.enabled} is true (the default).
 *
 * <p>The proxy is transparent to {@link DataSource#unwrap}, so Hikari metrics, Flyway and the pool
 * statistics in {@link RequestMetricsFilter} still reach the underlying pool.
 */
public class QueryStatsDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSource ds) || bean instanceof TimedDataSource || bean instanceof ProxyDataSource) {
            return bean;
        }
        ProxyDataSource proxy = ProxyDataSourceBuilder.create(ds)
                .listener(new StatsListener())
                .build();
        return new TimedDataSource(proxy);
    }

    /** Times {@code getConnection()}: with a bounded pool that is the wait for a free connection. */
    static final class TimedDataSource extends DelegatingDataSource {
        TimedDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            long start = System.nanoTime();
            try {
                return super.getConnection();
            } finally {
                RequestStats.recordConnectionCheckout(System.nanoTime() - start);
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            long start = System.nanoTime();
            try {
                return super.getConnection(username, password);
            } finally {
                RequestStats.recordConnectionCheckout(System.nanoTime() - start);
            }
        }
    }

    /** Statements do not nest on a thread, so a single start time per thread is enough. */
    static final class StatsListener implements QueryExecutionListener {
        private final ThreadLocal<Long> started = new ThreadLocal<>();

        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            started.set(System.nanoTime());
        }

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            Long start = started.get();
            started.remove();
            long nanos = start != null ? System.nanoTime() - start : execInfo.getElapsedTime() * 1_000_000L;
            if (queryInfoList.isEmpty()) {
                return;
            }
            // A JDBC batch is ONE round trip, so it adds one to dbQueries (the figure that predicts latency) while
            // every row is still listed in the per-statement counts, so topSql shows how big the batch was.
            RequestStats.recordQuery(queryInfoList.get(0).getQuery(), nanos, 1);
            for (int i = 1; i < queryInfoList.size(); i++) {
                RequestStats.recordBatchedRow(queryInfoList.get(i).getQuery());
            }
        }
    }
}
