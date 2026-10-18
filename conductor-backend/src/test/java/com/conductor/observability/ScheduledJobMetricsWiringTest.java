package com.conductor.observability;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** A lightweight (non-Boot) context proving a real scheduled method runs inside a RequestStats scope. */
class ScheduledJobMetricsWiringTest {

    static final CountDownLatch RAN = new CountDownLatch(1);
    static final AtomicBoolean SCOPE_OPEN = new AtomicBoolean();

    @Configuration
    @EnableScheduling
    static class Config {
        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
            s.setPoolSize(1);
            return s;
        }

        @Bean
        Ticker ticker() {
            return new Ticker();
        }
    }

    static class Ticker {
        @Scheduled(fixedDelay = 60_000, initialDelay = 10)
        public void tick() {
            SCOPE_OPEN.set(RequestStats.get() != null);
            RAN.countDown();
        }
    }

    @Test
    void scheduledMethodsRunInsideAScope() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(Config.class, ObservabilityConfig.class);
            ctx.refresh();
            assertThat(RAN.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(SCOPE_OPEN).isTrue();
        }
    }
}
