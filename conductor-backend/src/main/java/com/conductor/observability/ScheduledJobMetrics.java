package com.conductor.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Times every scheduled-task run inside its own {@link RequestStats} scope and logs
 * {@code scheduled_job} when it exceeds the threshold, to show background work competing with user
 * requests for the DB pool.
 *
 * <p>Implemented by wrapping the {@link TaskScheduler} the scheduling registrar uses rather than as an AOP
 * aspect: it sees the {@link ScheduledMethodRunnable} (class.method) for every scheduled task without
 * proxying any bean or adding the AspectJ dependency. (A {@code TaskDecorator} on the pool does not work
 * for this: it receives an internal wrapper, not the method runnable.)
 */
public final class ScheduledJobMetrics {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobMetrics.class);
    static final long LOG_THRESHOLD_MS = 500;

    private ScheduledJobMetrics() {
    }

    public static TaskScheduler wrap(TaskScheduler delegate) {
        return new MetricsTaskScheduler(delegate);
    }

    static Runnable decorate(Runnable runnable) {
        return () -> {
            long start = System.nanoTime();
            try (RequestStats.Scope scope = RequestStats.start()) {
                try {
                    runnable.run();
                } finally {
                    report(runnable, scope.stats(), RequestStats.toMs(System.nanoTime() - start));
                }
            }
        };
    }

    static void report(Runnable runnable, RequestStats stats, long ms) {
        if (ms <= LOG_THRESHOLD_MS) {
            return;
        }
        log.atInfo()
                .addKeyValue("job", jobName(runnable))
                .addKeyValue("ms", ms)
                .addKeyValue("dbQueries", stats.dbQueries())
                .addKeyValue("dbMs", RequestStats.toMs(stats.dbNanos()))
                .addKeyValue("poolWaitMs", RequestStats.toMs(stats.poolWaitNanos()))
                .log("scheduled_job");
    }

    static String jobName(Runnable runnable) {
        if (runnable instanceof com.conductor.scheduling.NamedJob named) {
            return named.jobName();
        }
        if (runnable instanceof ScheduledMethodRunnable smr) {
            return AopUtils.getTargetClass(smr.getTarget()).getSimpleName() + "." + smr.getMethod().getName();
        }
        return runnable.getClass().getName();
    }

    private record MetricsTaskScheduler(TaskScheduler delegate) implements TaskScheduler {

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return delegate.schedule(decorate(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return delegate.schedule(decorate(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return delegate.scheduleAtFixedRate(decorate(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return delegate.scheduleAtFixedRate(decorate(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return delegate.scheduleWithFixedDelay(decorate(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return delegate.scheduleWithFixedDelay(decorate(task), delay);
        }

        @Override
        public java.time.Clock getClock() {
            return delegate.getClock();
        }
    }
}
