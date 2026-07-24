package com.conductor.workflow.schema;

import com.conductor.workflow.StepExecutionContext;
import com.conductor.workflow.StepResult;
import com.conductor.workflow.WorkflowExecutionBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link StepSchemaRegistry#verifyStepTypesMatchValidator()} — the {@code
 * @PostConstruct} fail-fast guard that this registry's step-type keys exactly match the set {@code
 * WorkflowValidator} builds from the wired {@link WorkflowExecutionBackend} beans (plus {@code
 * "condition"}). Doesn't need Spring: fakes the backend list the same way {@code WorkflowValidator}'s
 * test-only constructor lets tests scope its registry.
 */
class StepSchemaRegistryConsistencyTest {

    private static final String[] PRODUCTION_BACKEND_TYPES =
            {"http", "docker", "kestra", "integration", "agent", "claude-code", "action"};

    @Test
    void backendsMatchingRegistry_doesNotThrow() {
        StepSchemaRegistry registry = new StepSchemaRegistry(fakeBackends(PRODUCTION_BACKEND_TYPES));
        assertThatCode(registry::verifyStepTypesMatchValidator).doesNotThrowAnyException();
    }

    @Test
    void backendMissingFromValidator_throws() {
        // "action" dropped -- simulates the registry describing a step type no executor backs anymore.
        StepSchemaRegistry registry = new StepSchemaRegistry(
                fakeBackends("http", "docker", "kestra", "integration", "agent", "claude-code"));
        assertThatThrownBy(registry::verifyStepTypesMatchValidator)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match WorkflowValidator's allowed step types");
    }

    @Test
    void unknownExtraBackend_throws() {
        // A new executor bean registered without a matching StepSchemaRegistry entry.
        StepSchemaRegistry registry = new StepSchemaRegistry(List.of(
                fakeBackend("http"), fakeBackend("docker"), fakeBackend("kestra"),
                fakeBackend("integration"), fakeBackend("agent"), fakeBackend("claude-code"),
                fakeBackend("action"), fakeBackend("brand-new-step-type")));
        assertThatThrownBy(registry::verifyStepTypesMatchValidator)
                .isInstanceOf(IllegalStateException.class);
    }

    private List<WorkflowExecutionBackend> fakeBackends(String... types) {
        List<WorkflowExecutionBackend> backends = new ArrayList<>();
        for (String type : types) {
            backends.add(fakeBackend(type));
        }
        return backends;
    }

    private WorkflowExecutionBackend fakeBackend(String type) {
        return new WorkflowExecutionBackend() {
            @Override
            public String getStepType() {
                return type;
            }

            @Override
            public StepResult execute(StepExecutionContext context) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }
}
