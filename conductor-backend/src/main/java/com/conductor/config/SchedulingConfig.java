package com.conductor.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on every timer-driven method in the application. On by default; {@code
 * conductor.scheduling.enabled=false} leaves all of them dormant, which is only ever wanted by a test that
 * drives a scheduler by calling its tick method directly ({@code KnowledgeIngestSchedulerIntegrationTest},
 * {@code ConnectorFeedSchedulerIntegrationTest}). Those tests boot their own Spring context with the
 * scheduler's own feature flag flipped on, and a {@code fixedDelay} timer fires its first tick the moment
 * the context is up — on the scheduler's thread, while the first test is already inserting the rows it
 * is about to assert on. Nothing in the test can stop that tick; this switch can.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "conductor.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
