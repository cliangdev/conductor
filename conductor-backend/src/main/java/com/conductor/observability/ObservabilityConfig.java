package com.conductor.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class ObservabilityConfig {

    /** Static: a BeanPostProcessor must be created before the DataSource it wraps. */
    @Bean
    @ConditionalOnProperty(name = "observability.datasource-proxy.enabled", havingValue = "true", matchIfMissing = true)
    static QueryStatsDataSourcePostProcessor queryStatsDataSourcePostProcessor() {
        return new QueryStatsDataSourcePostProcessor();
    }

    /**
     * Routes the scheduling infrastructure through {@link ScheduledJobMetrics#wrap} so every job run is
     * timed. The scheduler bean itself is untouched; only the registrar that runs the scheduled methods
     * uses the wrapper. With no scheduler bean, Spring's default behavior is left alone.
     */
    @Bean
    @ConditionalOnProperty(name = "observability.scheduled-jobs.enabled", havingValue = "true", matchIfMissing = true)
    SchedulingConfigurer scheduledJobMetricsConfigurer(ObjectProvider<TaskScheduler> scheduler) {
        return (ScheduledTaskRegistrar registrar) -> {
            TaskScheduler delegate = scheduler.getIfUnique();
            if (delegate != null) {
                registrar.setScheduler(ScheduledJobMetrics.wrap(delegate));
            }
        };
    }
}
