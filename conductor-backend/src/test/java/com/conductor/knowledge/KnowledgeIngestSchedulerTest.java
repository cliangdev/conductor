package com.conductor.knowledge;

import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One lane's dispatch failure must be that lane's alone. Before this, an exception from the first
 * lane's dispatch unwound the whole project's tick and the lanes after it never got claimed — which is
 * what the integration test's "other lanes still dispatch" case saw whenever something collided under
 * the first dispatch. Also covers which sources one claim takes, and when the sweep marks a source to
 * be retried alone.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestSchedulerTest {

    @Mock KnowledgeSourceRepository sourceRepository;
    @Mock ProjectSettingsService projectSettingsService;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock LibrarianDispatchService dispatchService;
    KnowledgeIngestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KnowledgeIngestScheduler(sourceRepository, projectSettingsService, workflowRepository,
                workflowRunRepository, stepRunRepository, dispatchService, true);
        scheduler.self = scheduler;
    }

    private static KnowledgeSource pending(String id) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setStatus(KnowledgeSourceStatus.PENDING);
        return source;
    }

    private static KnowledgeSource isolated(String id) {
        KnowledgeSource source = pending(id);
        source.setAttempts(1);
        source.setIsolateOnRetry(true);
        return source;
    }

    @Test
    void aLaneWhoseDispatchThrows_doesNotStopTheLanesAfterIt() {
        when(sourceRepository.findProjectIdsWithDuePending(any())).thenReturn(List.of("p1"));
        when(projectSettingsService.isKnowledgeEnabled("p1")).thenReturn(true);
        when(workflowRepository.findByProjectIdAndName(eq("p1"), anyString())).thenReturn(Optional.empty());
        when(sourceRepository.findLanesWithDuePending(eq("p1"), any())).thenReturn(java.util.Arrays.asList(null, "product"));
        when(sourceRepository.existsProcessingInLane(eq("p1"), any())).thenReturn(false);
        when(sourceRepository.findDuePendingForProjectAndDomain(eq("p1"), any(), any(OffsetDateTime.class), anyInt()))
                .thenAnswer(inv -> List.of(pending("src-" + inv.getArgument(1))));
        when(sourceRepository.findByStatus(KnowledgeSourceStatus.PROCESSING)).thenReturn(List.of());
        doThrow(new IllegalStateException("duplicate key value violates unique constraint"))
                .when(dispatchService).dispatch("p1", null, List.of("src-null"), false);

        scheduler.poll();

        // The lane after the failing one was still claimed and dispatched.
        verify(dispatchService).dispatch("p1", "product", List.of("src-product"), false);
    }

    @Test
    void freshSources_andOnesRetriedAfterAnOutage_areClaimedAsOneBatch() {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeSource afterOutage = pending("b");
        afterOutage.setAttempts(2); // failed before, but not inside the agent
        when(sourceRepository.findDuePendingForProjectAndDomain("p1", "engineering", now, 10))
                .thenReturn(List.of(pending("a"), afterOutage, pending("c")));

        KnowledgeIngestScheduler.Claim claim = scheduler.claimBatchInNewTx("p1", "engineering", now);

        assertThat(claim.sourceIds()).containsExactly("a", "b", "c");
        assertThat(claim.isolated()).isFalse();
    }

    @Test
    void anOldestSourceThatFailedInTheAgent_isClaimedAlone() {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeSource poison = isolated("poison");
        KnowledgeSource fresh = pending("fresh");
        when(sourceRepository.findDuePendingForProjectAndDomain("p1", "engineering", now, 10))
                .thenReturn(List.of(poison, fresh));

        KnowledgeIngestScheduler.Claim claim = scheduler.claimBatchInNewTx("p1", "engineering", now);

        assertThat(claim.sourceIds()).containsExactly("poison");
        assertThat(claim.isolated()).isTrue();
        assertThat(poison.getStatus()).isEqualTo(KnowledgeSourceStatus.PROCESSING);
        assertThat(fresh.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        verify(sourceRepository).saveAll(List.of(poison));
    }

    @Test
    void anIsolatedSourceBehindFreshOnes_isLeftOutOfTheirBatch() {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeSource poison = isolated("poison");
        when(sourceRepository.findDuePendingForProjectAndDomain("p1", "engineering", now, 10))
                .thenReturn(List.of(pending("a"), poison, pending("b")));

        KnowledgeIngestScheduler.Claim claim = scheduler.claimBatchInNewTx("p1", "engineering", now);

        assertThat(claim.sourceIds()).containsExactly("a", "b");
        assertThat(claim.isolated()).isFalse();
        assertThat(poison.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
    }

    private KnowledgeSource processing(String id, String runId) {
        KnowledgeSource source = pending(id);
        source.setStatus(KnowledgeSourceStatus.PROCESSING);
        source.setProcessingRunId(runId);
        when(sourceRepository.findById(id)).thenReturn(Optional.of(source));
        return source;
    }

    @Test
    void aRunThatFailedInTheAgent_marksItsSourcesToBeRetriedAlone() {
        KnowledgeSource source = processing("s1", "run-1");
        when(stepRunRepository.findFirstFailureReasonByRunId("run-1")).thenReturn(List.of("CLAUDE_AGENT_ERROR"));

        scheduler.resurrectOrDeadInNewTx("s1");

        assertThat(source.getStatus()).isEqualTo(KnowledgeSourceStatus.PENDING);
        assertThat(source.getAttempts()).isEqualTo(1);
        assertThat(source.isIsolateOnRetry()).isTrue();
    }

    @Test
    void aRunThatNeverReachedTheAgent_doesNotMarkItsSources() {
        KnowledgeSource source = processing("s1", "run-1");
        when(stepRunRepository.findFirstFailureReasonByRunId("run-1")).thenReturn(List.of("CLAUDE_LAUNCH_ERROR"));

        scheduler.resurrectOrDeadInNewTx("s1");

        assertThat(source.getAttempts()).isEqualTo(1);
        assertThat(source.isIsolateOnRetry()).isFalse();
    }
}
