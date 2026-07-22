package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowFailureCircuitBreakerTest {

    @Mock com.conductor.repository.WorkflowDefinitionRepository workflowRepository;
    @Mock NotificationDispatcher notificationDispatcher;

    WorkflowFailureCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new WorkflowFailureCircuitBreaker(workflowRepository, notificationDispatcher);
    }

    private WorkflowDefinition workflow(int consecutiveFailures) {
        Project project = new Project();
        project.setId("proj-1");
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.setId("wf-1");
        wf.setName("Knowledge Librarian");
        wf.setProject(project);
        wf.setEnabled(true);
        wf.setConsecutiveFailures(consecutiveFailures);
        return wf;
    }

    private WorkflowRun run(WorkflowDefinition workflow, WorkflowRunStatus status, String id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setWorkflow(workflow);
        run.setStatus(status);
        return run;
    }

    @Test
    void failedRun_incrementsCounter_belowThreshold_doesNotDisable() {
        WorkflowDefinition wf = workflow(2);

        breaker.recordOutcome(run(wf, WorkflowRunStatus.FAILED, "run-3"));

        assertThat(wf.getConsecutiveFailures()).isEqualTo(3);
        assertThat(wf.isEnabled()).isTrue();
        assertThat(wf.getAutoPausedAt()).isNull();
        verify(notificationDispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedRun_atThreshold_autoDisablesAndStampsReason() {
        WorkflowDefinition wf = workflow(WorkflowFailureCircuitBreaker.TRIP_THRESHOLD - 1);

        breaker.recordOutcome(run(wf, WorkflowRunStatus.FAILED, "run-tripping"));

        assertThat(wf.getConsecutiveFailures()).isEqualTo(WorkflowFailureCircuitBreaker.TRIP_THRESHOLD);
        assertThat(wf.isEnabled()).isFalse();
        assertThat(wf.getAutoPausedAt()).isNotNull();
        assertThat(wf.getAutoPauseReason()).isEqualTo(WorkflowFailureCircuitBreaker.REASON_CONSECUTIVE_FAILURES);
        assertThat(wf.getAutoPausedRunId()).isEqualTo("run-tripping");

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.WORKFLOW_AUTO_PAUSED);
        assertThat(captor.getValue().getMetadata()).containsEntry("runId", "run-tripping");
    }

    @Test
    void failedRun_pastThresholdWhileAlreadyDisabled_doesNotReTripOrRenotify() {
        WorkflowDefinition wf = workflow(WorkflowFailureCircuitBreaker.TRIP_THRESHOLD);
        wf.setEnabled(false);
        OffsetDateTime firstTripTime = OffsetDateTime.now().minusMinutes(1);
        wf.setAutoPausedAt(firstTripTime);
        wf.setAutoPauseReason(WorkflowFailureCircuitBreaker.REASON_CONSECUTIVE_FAILURES);
        wf.setAutoPausedRunId("run-first-trip");

        // A run that was already in flight when the breaker tripped completes afterward.
        breaker.recordOutcome(run(wf, WorkflowRunStatus.FAILED, "run-late-straggler"));

        assertThat(wf.getAutoPausedAt()).isEqualTo(firstTripTime);
        assertThat(wf.getAutoPausedRunId()).isEqualTo("run-first-trip");
        verify(notificationDispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void successfulRun_resetsCounterAndClearsPauseMarkers() {
        WorkflowDefinition wf = workflow(3);
        wf.setAutoPausedAt(OffsetDateTime.now());
        wf.setAutoPauseReason(WorkflowFailureCircuitBreaker.REASON_CONSECUTIVE_FAILURES);
        wf.setAutoPausedRunId("run-old-trip");

        breaker.recordOutcome(run(wf, WorkflowRunStatus.SUCCESS, "run-ok"));

        assertThat(wf.getConsecutiveFailures()).isZero();
        assertThat(wf.getAutoPausedAt()).isNull();
        assertThat(wf.getAutoPauseReason()).isNull();
        assertThat(wf.getAutoPausedRunId()).isNull();
    }

    @Test
    void nonTerminalStatus_isIgnored() {
        WorkflowDefinition wf = workflow(2);

        breaker.recordOutcome(run(wf, WorkflowRunStatus.CANCELLED, "run-cancelled"));

        assertThat(wf.getConsecutiveFailures()).isEqualTo(2);
        verify(workflowRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
