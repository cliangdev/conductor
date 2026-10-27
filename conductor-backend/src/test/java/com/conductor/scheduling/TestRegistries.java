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

import static org.mockito.Mockito.mock;

/** A registry over mock beans, for tests that assert a job's declared cadence. */
public final class TestRegistries {

    private TestRegistries() {}

    public static ScheduledJobRegistry mocked() {
        return new ScheduledJobRegistry(mock(MarketingInsightsFeedProvisioner.class), mock(ConnectorFeedScheduler.class),
                mock(KnowledgeIngestScheduler.class), mock(KnowledgeRetentionService.class),
                mock(MemoryMaintenanceScheduler.class), mock(ActionInvocationService.class),
                mock(DaemonEventService.class), mock(NativeHandoffService.class),
                mock(NativePublishConfirmationPoller.class), mock(PublishOutcomeService.class),
                mock(WebhookRetryScheduler.class), mock(PostPublishScheduler.class),
                mock(WorkflowExecutionEngine.class), mock(WorkflowScheduler.class));
    }

    public static java.time.Duration fixedDelay(String job) {
        return ((ScheduledJobDefinition.Trigger.FixedDelay) mocked().find(job).orElseThrow().inAppTrigger()).delay();
    }
}
