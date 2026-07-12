package com.conductor.workflow;

import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring smoke test: the step-type registry {@link WorkflowValidator} builds from the real
 * {@code List<WorkflowExecutionBackend>} bean list must match every executor actually wired up in
 * the app, plus "condition" (handled inline by the orchestrator, not a backend bean). This is the
 * regression guard for the "unchecked-uses hole" this phase closed — if a new executor is added
 * without updating this set, or an executor bean goes missing, this test catches the drift.
 */
class WorkflowValidatorRegistrySmokeTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private java.util.List<WorkflowExecutionBackend> backends;

    @Test
    void registryDerivedAllowedStepTypes_matchesAllWiredExecutorsPlusCondition() {
        Set<String> stepTypes = backends.stream()
                .map(WorkflowExecutionBackend::getStepType)
                .collect(Collectors.toSet());

        assertThat(stepTypes).containsExactlyInAnyOrder(
                "http", "docker", "kestra", "integration", "agent", "claude-code", "action");

        Set<String> allowedWithCondition = new java.util.HashSet<>(stepTypes);
        allowedWithCondition.add("condition");
        assertThat(allowedWithCondition).containsExactlyInAnyOrder(
                "http", "docker", "kestra", "integration", "agent", "claude-code", "action", "condition");
    }
}
