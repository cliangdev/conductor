package com.conductor.scheduling;

import com.conductor.integration.connector.marketing.MarketingInsightsFeedProvisioner;
import com.conductor.integration.ingest.ConnectorFeedScheduler;
import com.conductor.knowledge.KnowledgeIngestScheduler;
import com.conductor.knowledge.KnowledgeRetentionService;
import com.conductor.memory.MemoryMaintenanceScheduler;
import com.conductor.scheduling.ScheduledJobDefinition.Trigger;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.DaemonEventService;
import com.conductor.service.NativeHandoffService;
import com.conductor.service.NativePublishConfirmationPoller;
import com.conductor.service.PublishOutcomeService;
import com.conductor.service.WebhookRetryScheduler;
import com.conductor.workflow.PostPublishScheduler;
import com.conductor.workflow.WorkflowExecutionEngine;
import com.conductor.workflow.WorkflowScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every timed job in the application, by stable name. Bodies are the existing tick methods, called on the
 * injected beans (so {@code @Transactional} proxies apply); each tick keeps its own enabled flag and logic.
 *
 * <p>{@code WorkflowRunLogBroker#closeTerminalRunEmitters} is deliberately NOT here: it closes SSE emitters
 * held in this instance's memory every few seconds, so it only makes sense on the instance that owns them
 * and must stay an in-app {@code @Scheduled} timer.
 *
 * <p>In-app initial delays are staggered so the pollers do not all hit the database at start-up.
 */
@Component
public class ScheduledJobRegistry {

    // There is no lease heartbeat, so a TTL must exceed the job's worst-case runtime or another instance
    // could start the same job while it is still running. A crashed holder costs at most one TTL of delay.
    /** Cheap DB-only ticks. */
    private static final Duration SHORT_TTL = Duration.ofMinutes(5);
    /** Ticks that make many outbound HTTP pulls or dispatch the librarian (connector feeds, knowledge ingest, reconcile). */
    private static final Duration LONG_TTL = Duration.ofMinutes(15);
    private static final Duration HOURLY_TTL = Duration.ofMinutes(45);
    /** Daily crons; memory maintenance makes LLM calls per project. */
    private static final Duration DAILY_TTL = Duration.ofHours(2);

    private final List<ScheduledJobDefinition> jobs = new ArrayList<>();

    public ScheduledJobRegistry(MarketingInsightsFeedProvisioner marketingInsightsFeedProvisioner,
                                ConnectorFeedScheduler connectorFeedScheduler,
                                KnowledgeIngestScheduler knowledgeIngestScheduler,
                                KnowledgeRetentionService knowledgeRetentionService,
                                MemoryMaintenanceScheduler memoryMaintenanceScheduler,
                                ActionInvocationService actionInvocationService,
                                DaemonEventService daemonEventService,
                                NativeHandoffService nativeHandoffService,
                                NativePublishConfirmationPoller nativePublishConfirmationPoller,
                                PublishOutcomeService publishOutcomeService,
                                WebhookRetryScheduler webhookRetryScheduler,
                                PostPublishScheduler postPublishScheduler,
                                WorkflowExecutionEngine workflowExecutionEngine,
                                WorkflowScheduler workflowScheduler) {
        fixedDelay("marketing-insights-feed-provisioner", 3_600_000, 80_000, HOURLY_TTL,
                marketingInsightsFeedProvisioner::onSchedule);
        fixedDelay("connector-feed-poll", 60_000, 45_000, LONG_TTL, connectorFeedScheduler::poll);
        fixedDelay("knowledge-ingest-poll", 30_000, 20_000, LONG_TTL, knowledgeIngestScheduler::poll);
        fixedDelay("knowledge-retention-sweep", 3_600_000, 90_000, HOURLY_TTL, knowledgeRetentionService::sweep);
        cron("memory-maintenance", "0 17 3 * * *", DAILY_TTL, memoryMaintenanceScheduler::run);
        fixedDelay("action-invocation-retry", 60_000, 25_000, SHORT_TTL,
                actionInvocationService::retryFailedInvocations);
        fixedDelay("daemon-event-purge", 3_600_000, 85_000, HOURLY_TTL, daemonEventService::purgeExpiredEvents);
        fixedDelay("native-handoff-sweep", 60_000, 40_000, SHORT_TTL, nativeHandoffService::sweepDeferredHandoffs);
        fixedDelay("native-publish-confirmation-poll", 60_000, 55_000, SHORT_TTL,
                nativePublishConfirmationPoller::poll);
        fixedDelay("publish-reconcile-stranded-targets", 300_000, 65_000, LONG_TTL,
                publishOutcomeService::reconcileStrandedTargets);
        fixedDelay("webhook-retry", 60_000, 70_000, SHORT_TTL, webhookRetryScheduler::retryFailedEvents);
        fixedDelay("post-publish-poll", 30_000, 5_000, SHORT_TTL, postPublishScheduler::poll);
        cron("workflow-stuck-run-cleanup", "0 0 2 * * *", DAILY_TTL, workflowExecutionEngine::cleanupStuckRuns);
        fixedDelay("workflow-local-pickup-timeout", 60_000, 35_000, SHORT_TTL,
                workflowScheduler::timeoutLocalPickupRuns);
        fixedDelay("workflow-schedule-poll", 30_000, 10_000, SHORT_TTL, workflowScheduler::poll);
    }

    public List<ScheduledJobDefinition> jobs() {
        return List.copyOf(jobs);
    }

    public Optional<ScheduledJobDefinition> find(String name) {
        return jobs.stream().filter(j -> j.name().equals(name)).findFirst();
    }

    /** minSpacing is half the delay, so the in-app cadence (delay measured from completion) is never throttled. */
    private void fixedDelay(String name, long delayMs, long initialDelayMs, Duration leaseTtl, Runnable body) {
        jobs.add(new ScheduledJobDefinition(name,
                new Trigger.FixedDelay(Duration.ofMillis(delayMs), Duration.ofMillis(initialDelayMs)),
                leaseTtl, Duration.ofMillis(delayMs / 2), body));
    }

    private void cron(String name, String expression, Duration leaseTtl, Runnable body) {
        jobs.add(new ScheduledJobDefinition(name, new Trigger.Cron(expression), leaseTtl,
                Duration.ofHours(12), body));
    }
}
