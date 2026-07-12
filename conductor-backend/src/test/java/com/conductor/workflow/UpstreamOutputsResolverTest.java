package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.workflow.model.JobSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpstreamOutputsResolverTest {

    @Mock private WorkflowJobRunRepository jobRunRepository;
    @Mock private WorkflowStepRunRepository stepRunRepository;

    private UpstreamOutputsResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UpstreamOutputsResolver(jobRunRepository, stepRunRepository, new ObjectMapper());
    }

    private WorkflowStepRun stepRun(String stepId, String outputJson) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setStepId(stepId);
        step.setOutputJson(outputJson);
        return step;
    }

    @Test void mergesStepsInDeterministicOrder_laterStepWinsOnCollision() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");

        JobSpec jobA = new JobSpec("a", List.of(), null, null, null, List.of(), List.of(), Map.of());
        JobSpec jobB = new JobSpec("b", List.of("a"), null, null, null, List.of(), List.of(), Map.of());
        Map<String, JobSpec> jobs = Map.of("a", jobA, "b", jobB);

        WorkflowJobRun jobRunA = new WorkflowJobRun();
        jobRunA.setId("jobrun-a");
        jobRunA.setJobId("a");
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "a"))
                .thenReturn(List.of(jobRunA));

        // "first" writes x=1; "second" (later in execution order) overwrites x and adds y — the
        // ordered repository method is what guarantees "second" is applied last.
        when(stepRunRepository.findByJobRunIdOrderByStartedAtAscIdAsc("jobrun-a"))
                .thenReturn(List.of(
                        stepRun("first", "{\"x\":\"1\"}"),
                        stepRun("second", "{\"x\":\"2\",\"y\":\"3\"}")));

        Map<String, Map<String, String>> result = resolver.collectUpstreamOutputs(run, jobs, "b");

        assertThat(result.get("a")).containsExactlyInAnyOrderEntriesOf(Map.of("x", "2", "y", "3"));
    }

    @Test void noUpstreamJobRun_yieldsNoEntryForThatJob() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");

        JobSpec jobB = new JobSpec("b", List.of("a"), null, null, null, List.of(), List.of(), Map.of());
        Map<String, JobSpec> jobs = Map.of("b", jobB);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "a"))
                .thenReturn(List.of());

        Map<String, Map<String, String>> result = resolver.collectUpstreamOutputs(run, jobs, "b");

        assertThat(result).doesNotContainKey("a");
    }
}
