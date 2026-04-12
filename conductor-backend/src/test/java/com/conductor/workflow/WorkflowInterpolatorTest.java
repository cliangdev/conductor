package com.conductor.workflow;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowInterpolatorTest {

    private final WorkflowInterpolator interpolator = new WorkflowInterpolator();

    @Test void interpolatesEventField() {
        RuntimeContext ctx = new RuntimeContext(Map.of("issueId", "abc"), Map.of(), Map.of(), Map.of());
        assertEquals("abc", interpolator.interpolate("${{ event.issueId }}", ctx));
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
}
