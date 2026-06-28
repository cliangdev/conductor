package com.conductor.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void isLifecycleTrueForNonEmptyStatechart() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setDefinition(mapper.readTree("{\"id\":\"ENGINEERING\",\"statuses\":[]}"));
        assertThat(def.isLifecycle()).isTrue();
    }

    @Test
    void isLifecycleFalseForNullDefinition() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setDefinition(null);
        assertThat(def.isLifecycle()).isFalse();
    }

    @Test
    void isLifecycleFalseForEmptyObject() throws Exception {
        // The exact regression shape: a YAML automation whose definition is an empty {} must not be
        // misclassified as a lifecycle (statechart) Workflow.
        WorkflowDefinition def = new WorkflowDefinition();
        def.setDefinition(mapper.readTree("{}"));
        assertThat(def.isLifecycle()).isFalse();
    }
}
