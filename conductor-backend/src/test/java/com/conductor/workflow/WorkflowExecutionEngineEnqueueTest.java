package com.conductor.workflow;

import com.conductor.entity.WorkflowJobQueue;
import com.conductor.entity.WorkflowRun;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobQueueRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code enqueueJob}'s best-effort de-dup: two upstream jobs completing near-simultaneously (the
 * finalizeJob path and the completeRemoteJob path, e.g. a diamond {@code needs}) can each try to
 * enqueue the same dependent — this must not insert a second unclaimed queue row.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineEnqueueTest {

    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowJobOrchestrator orchestrator;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;
    @Mock WorkflowRunFailureNotifier runFailureNotifier;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, runFailureNotifier, 4);
    }

    @Test
    void enqueueJob_skipsInsert_whenUnclaimedRowAlreadyExistsForRunAndJob() {
        when(queueRepository.findByRunIdAndJobIdAndClaimedAtIsNull("run-1", "notify"))
                .thenReturn(List.of(mock(WorkflowJobQueue.class)));

        engine.enqueueJob("run-1", "notify");

        verify(queueRepository, never()).save(any());
        verify(runRepository, never()).findById(any());
    }

    @Test
    void enqueueJob_insertsNewRow_whenNoUnclaimedRowExists() {
        when(queueRepository.findByRunIdAndJobIdAndClaimedAtIsNull("run-1", "notify"))
                .thenReturn(List.of());
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        engine.enqueueJob("run-1", "notify");

        verify(queueRepository).save(any(WorkflowJobQueue.class));
    }
}
