package com.conductor.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal recursive-descent evaluator for workflow if conditions.
 * Supports: ==, !=, >, <, &&, ||
 * Operands are string literals (single-quoted) or bare word values.
 * Returns false on any parse error (does not throw).
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    /**
     * Evaluates a condition expression (already interpolated — no ${{ }} in input).
     * The input may have been extracted from ${{ condition }} by stripping the outer delimiters.
     * Returns true if the condition is truthy, false otherwise.
     */
    public boolean evaluate(String expression) {
        if (expression == null || expression.isBlank()) return false;
        String expr = expression.trim();
        if (expr.startsWith("${{")) {
            if (!expr.endsWith("}}")) return false; // malformed delimiter
            expr = expr.substring(3, expr.length() - 2).trim();
        }
        try {
            return evalOr(new Parser(expr));
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    private boolean evalOr(Parser p) {
        boolean left = evalAnd(p);
        while (p.matches("||")) {
            boolean right = evalAnd(p);
            left = left || right;
        }
        return left;
    }

    private boolean evalAnd(Parser p) {
        boolean left = evalComparison(p);
        while (p.matches("&&")) {
            boolean right = evalComparison(p);
            left = left && right;
        }
        return left;
    }

    private boolean evalComparison(Parser p) {
        String left = p.readValue();
        p.skipWhitespace();
        if (p.matches("==")) {
            return left.equals(p.readValue());
        } else if (p.matches("!=")) {
            return !left.equals(p.readValue());
        } else if (p.matches(">")) {
            return compareNumeric(left, p.readValue()) > 0;
        } else if (p.matches("<")) {
            return compareNumeric(left, p.readValue()) < 0;
        }
        // Bare value: truthy if non-empty and not "false"
        return !left.isEmpty() && !left.equalsIgnoreCase("false");
    }

    private int compareNumeric(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        boolean matches(String token) {
            skipWhitespace();
            if (input.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        String readValue() {
            skipWhitespace();
            if (pos >= input.length()) return "";
            if (input.charAt(pos) == '\'') {
                pos++;
                int start = pos;
                while (pos < input.length() && input.charAt(pos) != '\'') pos++;
                String val = input.substring(start, pos);
                if (pos < input.length()) pos++; // consume closing quote
                return val;
            }
            // Bare word (up to whitespace or operator)
            int start = pos;
            while (pos < input.length() && !Character.isWhitespace(input.charAt(pos))
                    && !input.startsWith("==", pos) && !input.startsWith("!=", pos)
                    && !input.startsWith("&&", pos) && !input.startsWith("||", pos)
                    && input.charAt(pos) != '>' && input.charAt(pos) != '<') {
                pos++;
            }
            return input.substring(start, pos);
        }
    }
}
