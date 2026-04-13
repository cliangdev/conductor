package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLoopInterpolationTest {

    private final WorkflowInterpolator interpolator = new WorkflowInterpolator();

    @Test
    void loopIterationResolvesToCurrentIteration() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 3);
        assertThat(interpolator.interpolate("${{ loop.iteration }}", ctx)).isEqualTo("3");
    }

    @Test
    void loopIterationZeroWhenNotInLoop() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0);
        assertThat(interpolator.interpolate("${{ loop.iteration }}", ctx)).isEqualTo("0");
    }

    @Test
    void loopIterationUsableInConditionExpression() {
        ConditionEvaluator evaluator = new ConditionEvaluator();
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 5);
        String interpolated = interpolator.interpolate("${{ loop.iteration }} > 3", ctx);
        assertThat(evaluator.evaluate(interpolated)).isTrue();
    }

    @Test
    void loopIterationFirstIterationIs1Based() {
        // iteration field 0 → loop.iteration = 1 (1-based, set by orchestrator)
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 1);
        assertThat(interpolator.interpolate("${{ loop.iteration }}", ctx)).isEqualTo("1");
    }

    @Test
    void loopIterationUnknownKeyResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 2);
        assertThat(interpolator.interpolate("${{ loop.unknown_key }}", ctx)).isEqualTo("");
    }
}
