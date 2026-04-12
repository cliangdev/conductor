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
}
