package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.TriggersSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowTriggerServiceTest {

    private static final String WORKFLOW_ID = "wf-1";

    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock WorkflowExecutionEngine executionEngine;
    @Mock WorkflowScheduleRepository scheduleRepository;
    @Mock ObjectMapper objectMapper;
    @Mock WorkflowYamlParser yamlParser;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;

    WorkflowTriggerService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerService(workflowRepository, workflowRunRepository, executionEngine,
                scheduleRepository, objectMapper, yamlParser, circuitBreaker);
        // Echo back whatever run is saved, same shape createRun/the fail-fast branch rely on.
        when(workflowRunRepository.save(any(WorkflowRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private WorkflowDefinition workflow() {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId(WORKFLOW_ID);
        workflow.setName("Knowledge Bootstrap");
        workflow.setYaml("name: Knowledge Bootstrap");
        return workflow;
    }

    private WorkflowSpec specWithJobs(String concurrency, JobSpec... jobs) {
        Map<String, JobSpec> jobMap = new LinkedHashMap<>();
        for (JobSpec job : jobs) {
            jobMap.put(job.id(), job);
        }
        TriggersSpec triggers = new TriggersSpec(null, null, List.of(), List.of(), true, Map.of());
        return new WorkflowSpec("Knowledge Bootstrap", triggers, concurrency, jobMap, Map.of());
    }

    private JobSpec rootJob(String id) {
        return new JobSpec(id, List.of(), "cloud-run", null, null, List.of(), List.of(), Map.of());
    }

    private JobSpec dependentJob(String id, String needsId) {
        return new JobSpec(id, List.of(needsId), "cloud-run", null, null, List.of(), List.of(), Map.of());
    }

    @Test
    void triggerManual_marksRunFailed_whenYamlUnparsable() {
        WorkflowDefinition workflow = workflow();
        when(yamlParser.parse(anyString())).thenThrow(new WorkflowYamlException("malformed"));

        WorkflowRun run = service.triggerManual(workflow, "user-1");

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(run.getCompletedAt()).isNotNull();
        verify(executionEngine, never()).enqueueJob(any(), any());
        verify(circuitBreaker).recordOutcome(run);
    }

    @Test
    void triggerManual_marksRunFailed_whenNoJobIsEligibleToEnqueue() {
        WorkflowDefinition workflow = workflow();
        when(yamlParser.parse(anyString())).thenReturn(specWithJobs(null, dependentJob("only", "missing-parent")));

        WorkflowRun run = service.triggerManual(workflow, "user-1");

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(run.getCompletedAt()).isNotNull();
        verify(executionEngine, never()).enqueueJob(any(), any());
        verify(circuitBreaker).recordOutcome(run);
    }

    @Test
    void triggerManual_blocksSecondDispatch_whenConcurrencySingleAndActiveRunExists() {
        WorkflowDefinition workflow = workflow();
        when(yamlParser.parse(anyString())).thenReturn(specWithJobs("single", rootJob("bootstrap")));
        WorkflowRun activeRun = new WorkflowRun();
        activeRun.setId("run-active");
        when(workflowRunRepository.findByWorkflowIdAndStatusIn(eq(WORKFLOW_ID), any()))
                .thenReturn(List.of(activeRun));

        assertThatThrownBy(() -> service.triggerManual(workflow, "user-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has an active run");

        verify(workflowRunRepository, never()).save(any());
        verify(circuitBreaker, never()).recordOutcome(any());
    }

    @Test
    void triggerManual_succeeds_whenConcurrencySingleAndNoActiveRun() {
        WorkflowDefinition workflow = workflow();
        when(yamlParser.parse(anyString())).thenReturn(specWithJobs("single", rootJob("bootstrap")));
        when(workflowRunRepository.findByWorkflowIdAndStatusIn(eq(WORKFLOW_ID), any()))
                .thenReturn(List.of());

        WorkflowRun run = service.triggerManual(workflow, "user-1");

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
        verify(executionEngine).enqueueJob(run.getId(), "bootstrap");
        verify(circuitBreaker, never()).recordOutcome(any());
    }

    @Test
    void fireTrigger_isNotGatedByConcurrencySingle_evenWithAnActiveRun() {
        WorkflowDefinition workflow = workflow();
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        when(yamlParser.parse(anyString())).thenReturn(specWithJobs("single", rootJob("bootstrap")));

        WorkflowRun run = service.fireTrigger(WORKFLOW_ID, "workflow_dispatch", "{}");

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.PENDING);
        verify(workflowRunRepository, never()).findByWorkflowIdAndStatusIn(any(), any());
    }
}
