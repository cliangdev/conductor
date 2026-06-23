package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    private Statechart engineering() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            return Statechart.parse(mapper.readTree(in));
        }
    }

    @Test
    void canTransitionFollowsTheStatechartEdges() throws Exception {
        Statechart sc = engineering();
        assertThat(engine.canTransition(sc, "DRAFT", "IN_REVIEW")).isTrue();
        assertThat(engine.canTransition(sc, "IN_REVIEW", "DRAFT")).isTrue(); // back-edge
        assertThat(engine.canTransition(sc, "CODE_REVIEW", "DONE")).isTrue();
        assertThat(engine.canTransition(sc, "DRAFT", "READY_FOR_DEVELOPMENT")).isFalse();
        assertThat(engine.canTransition(sc, "DONE", "CLOSED")).isFalse(); // terminal, no out-edges
    }

    @Test
    void availableTransitionsReturnsOutgoingEdges() throws Exception {
        Statechart sc = engineering();
        assertThat(engine.availableTransitions(sc, "IN_REVIEW"))
                .extracting(StatechartTransition::to)
                .containsExactlyInAnyOrder("READY_FOR_DEVELOPMENT", "DRAFT", "CLOSED");
        assertThat(engine.availableTransitions(sc, "DONE")).isEmpty();
    }
}
