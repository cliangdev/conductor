package com.conductor.workflow;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowInterpolatorTest {

    private final WorkflowInterpolator interpolator = new WorkflowInterpolator();

    @Test void interpolatesEventField() {
        RuntimeContext ctx = new RuntimeContext(Map.of("workItemId", "abc"), Map.of(), Map.of(), Map.of());
        assertEquals("abc", interpolator.interpolate("${{ event.workItemId }}", ctx));
    }

    @Test void interpolatesSecret() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of("TOKEN", "secret123"), Map.of(), Map.of());
        assertEquals("Bearer secret123", interpolator.interpolate("Bearer ${{ secrets.TOKEN }}", ctx));
    }

    @Test void interpolatesNeedsOutput() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(),
                Map.of("check-pr", Map.of("state", "open")));
        assertEquals("open", interpolator.interpolate("${{ needs.check-pr.outputs.state }}", ctx));
    }

    @Test void unknownReferenceResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("", interpolator.interpolate("${{ event.missing }}", ctx));
    }

    @Test void multipleExpressionsInSingleString() {
        RuntimeContext ctx = new RuntimeContext(Map.of("id", "123", "title", "My PRD"), Map.of(), Map.of(), Map.of());
        assertEquals("Issue 123: My PRD", interpolator.interpolate("Issue ${{ event.id }}: ${{ event.title }}", ctx));
    }

    @Test void nullTemplateReturnsNull() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        assertNull(interpolator.interpolate(null, ctx));
    }

    @Test void interpolatesStepOutput() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(),
                Map.of("build", Map.of("artifact", "app.jar")), Map.of());
        assertEquals("app.jar", interpolator.interpolate("${{ steps.build.outputs.artifact }}", ctx));
    }

    @Test void templateWithNoExpressionsUnchanged() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("no expressions here", interpolator.interpolate("no expressions here", ctx));
    }

    @Test void interpolatesNeedsResult() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of("job-a", "failure"), Map.of(), Map.of());
        assertEquals("failure", interpolator.interpolate("${{ needs.job-a.result }}", ctx));
    }

    @Test void unknownNeedsResultResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of("job-a", "success"), Map.of(), Map.of());
        assertEquals("", interpolator.interpolate("${{ needs.job-b.result }}", ctx));
    }

    @Test void interpolatesStepsResult() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of(), Map.of("build", "failure"), Map.of());
        assertEquals("failure", interpolator.interpolate("${{ steps.build.result }}", ctx));
    }

    @Test void unknownStepsResultResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of(), Map.of("build", "success"), Map.of());
        assertEquals("", interpolator.interpolate("${{ steps.deploy.result }}", ctx));
    }

    @Test void interpolatesInputs() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of(), Map.of(), Map.of("environment", "staging"));
        assertEquals("staging", interpolator.interpolate("${{ inputs.environment }}", ctx));
    }

    @Test void unknownInputResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("", interpolator.interpolate("${{ inputs.missing }}", ctx));
    }

    @Test void interpolatesNeedsArtifact() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of(), Map.of(), Map.of(),
                Map.of("build", Map.of("report", "https://storage.example/signed-url")));
        assertEquals("https://storage.example/signed-url",
                interpolator.interpolate("${{ needs.build.artifacts.report }}", ctx));
    }

    @Test void unknownNeedsArtifactResolvesToEmpty() {
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of(), 0,
                Map.of(), Map.of(), Map.of(),
                Map.of("build", Map.of("report", "https://storage.example/signed-url")));
        assertEquals("", interpolator.interpolate("${{ needs.build.artifacts.missing }}", ctx));
        assertEquals("", interpolator.interpolate("${{ needs.other-job.artifacts.report }}", ctx));
    }
}
