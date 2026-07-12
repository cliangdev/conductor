package com.conductor.workflow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @Test void equalStringsTrue() {
        assertTrue(evaluator.evaluate("'open' == 'open'"));
    }

    @Test void unequalStringsFalse() {
        assertFalse(evaluator.evaluate("'open' == 'closed'"));
    }

    @Test void notEqualTrue() {
        assertTrue(evaluator.evaluate("'open' != 'closed'"));
    }

    @Test void andBothTrue() {
        assertTrue(evaluator.evaluate("'open' == 'open' && 'a' != 'b'"));
    }

    @Test void andOneFalse() {
        assertFalse(evaluator.evaluate("'open' == 'open' && 'a' == 'b'"));
    }

    @Test void orOneFalse() {
        assertTrue(evaluator.evaluate("'x' == 'y' || 'a' == 'a'"));
    }

    @Test void malformedReturnsFalse() {
        assertFalse(evaluator.evaluate("${{ ??? broken"));
    }

    @Test void nullReturnsFalse() {
        assertFalse(evaluator.evaluate(null));
    }

    @Test void bareValueTrue() {
        assertTrue(evaluator.evaluate("open"));
    }

    @Test void greaterThan() {
        assertTrue(evaluator.evaluate("'10' > '5'"));
    }

    @Test void lessThan() {
        assertTrue(evaluator.evaluate("'3' < '5'"));
    }

    @Test void falseStringReturnsFalse() {
        assertFalse(evaluator.evaluate("false"));
    }

    @Test void emptyStringReturnsFalse() {
        assertFalse(evaluator.evaluate(""));
    }

    @Test void outerDelimitersStripped() {
        assertTrue(evaluator.evaluate("${{ 'open' == 'open' }}"));
    }

    // ── always()/success()/failure() ──────────────────────────────────────────

    private static final ConditionStatusContext ALL_SUCCEEDED = new ConditionStatusContext(false, true);
    private static final ConditionStatusContext SOME_FAILED = new ConditionStatusContext(true, false);
    /** A need was SKIPPED (not FAILED): allUpstreamSucceeded is false, but nothing actually failed. */
    private static final ConditionStatusContext SOME_SKIPPED = new ConditionStatusContext(false, false);

    @Test void alwaysTrueRegardlessOfContext() {
        assertTrue(evaluator.evaluate("always()", ConditionStatusContext.NONE));
        assertTrue(evaluator.evaluate("always()", SOME_FAILED));
        assertTrue(evaluator.evaluate("always()", SOME_SKIPPED));
    }

    @Test void successTrueWhenAllUpstreamSucceeded() {
        assertTrue(evaluator.evaluate("success()", ALL_SUCCEEDED));
    }

    @Test void successFalseWhenUpstreamFailed() {
        assertFalse(evaluator.evaluate("success()", SOME_FAILED));
    }

    @Test void successFalseWhenUpstreamSkippedButNotFailed() {
        // A skipped (not failed) need still breaks the implicit success() — cascades like GitHub Actions.
        assertFalse(evaluator.evaluate("success()", SOME_SKIPPED));
    }

    @Test void failureTrueWhenUpstreamFailed() {
        assertTrue(evaluator.evaluate("failure()", SOME_FAILED));
    }

    @Test void failureFalseWhenNoUpstreamFailure() {
        assertFalse(evaluator.evaluate("failure()", ALL_SUCCEEDED));
    }

    @Test void failureFalseWhenUpstreamOnlySkippedNotFailed() {
        // A skip alone must not trip failure() — only a real FAILED/LOOP_EXHAUSTED need does.
        assertFalse(evaluator.evaluate("failure()", SOME_SKIPPED));
    }

    @Test void plainEvaluateOverloadUsesNoneContext() {
        // No ConditionStatusContext supplied — success() must behave as if nothing failed upstream.
        assertTrue(evaluator.evaluate("success()"));
        assertFalse(evaluator.evaluate("failure()"));
    }

    @Test void failureComposedWithAnd() {
        assertTrue(evaluator.evaluate("failure() && 'a' == 'a'", SOME_FAILED));
        assertFalse(evaluator.evaluate("failure() && 'a' == 'b'", SOME_FAILED));
    }

    @Test void successComposedWithOr() {
        assertTrue(evaluator.evaluate("success() || 'a' == 'a'", SOME_FAILED));
        assertFalse(evaluator.evaluate("success() || 'a' == 'b'", SOME_FAILED));
    }

    @Test void alwaysComposedWithFailureAnd() {
        assertTrue(evaluator.evaluate("always() && success()", ALL_SUCCEEDED));
        assertFalse(evaluator.evaluate("always() && failure()", ALL_SUCCEEDED));
    }

    @Test void successComparedToStringLiteral() {
        assertTrue(evaluator.evaluate("success() == 'true'", ALL_SUCCEEDED));
        assertTrue(evaluator.evaluate("failure() == 'false'", ALL_SUCCEEDED));
    }
}
