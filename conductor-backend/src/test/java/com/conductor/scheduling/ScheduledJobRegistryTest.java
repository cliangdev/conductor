package com.conductor.scheduling;

import com.conductor.integration.connector.marketing.MarketingInsightsFeedProvisioner;
import com.conductor.integration.ingest.ConnectorFeedScheduler;
import com.conductor.knowledge.KnowledgeIngestScheduler;
import com.conductor.knowledge.KnowledgeRetentionService;
import com.conductor.memory.MemoryMaintenanceScheduler;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.DaemonEventService;
import com.conductor.service.NativeHandoffService;
import com.conductor.service.NativePublishConfirmationPoller;
import com.conductor.service.PublishOutcomeService;
import com.conductor.service.WebhookRetryScheduler;
import com.conductor.workflow.PostPublishScheduler;
import com.conductor.workflow.WorkflowExecutionEngine;
import com.conductor.workflow.WorkflowScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ScheduledJobRegistryTest {

    private final MarketingInsightsFeedProvisioner marketing = mock(MarketingInsightsFeedProvisioner.class);
    private final ConnectorFeedScheduler feeds = mock(ConnectorFeedScheduler.class);
    private final KnowledgeIngestScheduler ingest = mock(KnowledgeIngestScheduler.class);
    private final KnowledgeRetentionService retention = mock(KnowledgeRetentionService.class);
    private final MemoryMaintenanceScheduler memory = mock(MemoryMaintenanceScheduler.class);
    private final ActionInvocationService actions = mock(ActionInvocationService.class);
    private final DaemonEventService daemon = mock(DaemonEventService.class);
    private final NativeHandoffService handoff = mock(NativeHandoffService.class);
    private final NativePublishConfirmationPoller confirm = mock(NativePublishConfirmationPoller.class);
    private final PublishOutcomeService outcomes = mock(PublishOutcomeService.class);
    private final WebhookRetryScheduler webhooks = mock(WebhookRetryScheduler.class);
    private final PostPublishScheduler publish = mock(PostPublishScheduler.class);
    private final WorkflowExecutionEngine engine = mock(WorkflowExecutionEngine.class);
    private final WorkflowScheduler workflows = mock(WorkflowScheduler.class);

    private ScheduledJobRegistry registry() {
        return new ScheduledJobRegistry(marketing, feeds, ingest, retention, memory, actions, daemon, handoff,
                confirm, outcomes, webhooks, publish, engine, workflows);
    }

    @Test
    void registryHoldsExactlyTheFormerScheduledJobs() {
        assertThat(registry().jobs()).extracting(ScheduledJobDefinition::name).containsExactlyInAnyOrder(
                "marketing-insights-feed-provisioner", "connector-feed-poll", "knowledge-ingest-poll",
                "knowledge-retention-sweep", "memory-maintenance", "action-invocation-retry",
                "daemon-event-purge", "native-handoff-sweep", "native-publish-confirmation-poll",
                "publish-reconcile-stranded-targets", "webhook-retry", "post-publish-poll",
                "workflow-stuck-run-cleanup", "workflow-local-pickup-timeout", "workflow-schedule-poll");
    }

    @Test
    void everyBodyCallsItsExistingTickMethod() {
        ScheduledJobRegistry r = registry();
        r.jobs().forEach(j -> j.body().run());

        verify(marketing).onSchedule();
        verify(feeds).poll();
        verify(ingest).poll();
        verify(retention).sweep();
        verify(memory).run();
        verify(actions).retryFailedInvocations();
        verify(daemon).purgeExpiredEvents();
        verify(handoff).sweepDeferredHandoffs();
        verify(confirm).poll();
        verify(outcomes).reconcileStrandedTargets();
        verify(webhooks).retryFailedEvents();
        verify(publish).poll();
        verify(engine).cleanupStuckRuns();
        verify(workflows).timeoutLocalPickupRuns();
        verify(workflows).poll();
    }

    @Test
    void onlyTheRunLogBrokerKeepsAScheduledAnnotation() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> annotated = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readAllLines(p).stream().anyMatch(l -> l.trim().startsWith("@Scheduled("));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).toList();
            assertThat(annotated).containsExactly("WorkflowRunLogBroker.java");
        }
    }

    @Test
    void inAppModeRegistersEveryJobAndExternalModeNone() {
        ScheduledJobRegistry r = registry();
        ScheduledJobRunner runner = mock(ScheduledJobRunner.class);
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withUserConfiguration(InAppScheduledJobs.class)
                .withBean(ScheduledJobRegistry.class, () -> r)
                .withBean(ScheduledJobRunner.class, () -> runner);

        base.run(ctx -> {
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            ctx.getBean(InAppScheduledJobs.class).configureTasks(registrar);
            assertThat(registrar.getFixedDelayTaskList()).hasSize(13);
            assertThat(registrar.getCronTaskList()).hasSize(2);
            registrar.getFixedDelayTaskList().forEach(t -> t.getRunnable().run());
            verify(runner).run("connector-feed-poll");
        });
        base.withPropertyValues("conductor.scheduling.mode=external")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(InAppScheduledJobs.class));
        base.withPropertyValues("conductor.scheduling.enabled=false").run(ctx -> {
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            ctx.getBean(InAppScheduledJobs.class).configureTasks(registrar);
            assertThat(registrar.hasTasks()).isFalse();
        });
    }

    @Test
    void inAppInitialDelaysAreThoseOfTheFormerAnnotations() {
        var feedsJob = registry().find("connector-feed-poll").orElseThrow();
        assertThat(feedsJob.inAppTrigger()).isEqualTo(new ScheduledJobDefinition.Trigger.FixedDelay(
                java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(45)));
    }
}
