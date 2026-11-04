package com.conductor.creative;

import com.conductor.workflow.RuntimeContext;
import com.conductor.workflow.WorkflowInterpolator;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded render workflow as the dispatcher's payload resolves it: {@code event.revisionId} must become the
 * empty string (not the literal expression) for an ordinary render, and the step's script must pick
 * {@code render-slides} exactly when it is set.
 */
class CreativeRenderWorkflowYamlTest {

    private final WorkflowInterpolator interpolator = new WorkflowInterpolator();
    private final WorkflowSpec spec = new WorkflowYamlParser().parse(CreativeWorkflowProvisioner.readResource());

    private RuntimeContext event(Map<String, Object> payload) {
        return new RuntimeContext(payload, Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> payload(boolean withRevision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", withRevision ? "creative.slideshow-text" : "creative.render");
        payload.put("creativeId", "cr-1");
        payload.put("runtime", "self-hosted");
        if (withRevision) {
            payload.put("revisionId", "rv-1");
        }
        return payload;
    }

    private StepSpec step(String job) {
        return spec.jobs().get(job).executableSteps().get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> env(String job, boolean withRevision) {
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<String, Object>) step(job).raw().get("env")).forEach((k, v) ->
                out.put(k, interpolator.interpolate(v.toString(), event(payload(withRevision)))));
        return out;
    }

    @Test
    void aMissingRevisionIdResolvesToEmptyNotTheLiteralExpression() {
        for (String job : List.of("render", "render-target")) {
            assertThat(env(job, false)).containsEntry("CREATIVE_ID", "cr-1").containsEntry("REVISION_ID", "");
            assertThat(env(job, true)).containsEntry("CREATIVE_ID", "cr-1").containsEntry("REVISION_ID", "rv-1");
        }
    }

    @Test
    void bothJobsRunTheSameSingleLineScriptThatPicksTheCommandByRevisionId() {
        String script = interpolator.interpolate(step("render").raw().get("run").toString(), event(payload(true)));
        assertThat(script).isEqualTo(interpolator.interpolate(step("render-target").raw().get("run").toString(),
                event(payload(true))));
        assertThat(script).doesNotContain("\n").doesNotContain("${{")
                .contains("conductor creative render-slides \"$REVISION_ID\" --creative \"$CREATIVE_ID\"")
                .contains("conductor creative render \"$CREATIVE_ID\" --renderer workflow");
    }

    @Test
    void theScriptRunsRenderSlidesWithARevisionAndRenderWithout() throws Exception {
        String script = interpolator.interpolate(step("render").raw().get("run").toString(), event(payload(true)));

        assertThat(runScript(script, "rv-1")).isEqualTo("render-slides rv-1 --creative cr-1 --workflow-run-id run-9");
        assertThat(runScript(script, "")).isEqualTo("render cr-1 --renderer workflow --workflow-run-id run-9");
    }

    /** Runs {@code script} in sh with a stub `conductor` that echoes its arguments (minus "creative"). */
    private static String runScript(String script, String revisionId) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "conductor() { shift; echo \"$@\"; }\n" + script);
        builder.environment().put("CREATIVE_ID", "cr-1");
        builder.environment().put("REVISION_ID", revisionId);
        builder.environment().put("CONDUCTOR_WORKFLOW_RUN_ID", "run-9");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return output;
    }
}
