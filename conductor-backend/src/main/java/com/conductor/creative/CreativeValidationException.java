package com.conductor.creative;

import java.util.List;

/**
 * Thrown by {@link CreativeValidator} when a Creative fails a structural rule (unknown
 * layout/theme/placement, the kit's accent-phrase rule, a Brand Kit copy rule, sequence bounds) or, when
 * the Creative is or is becoming {@code READY}, a readiness rule (missing photo, blank headline, ...).
 *
 * <p>Carries every violation found, not just the first — {@code GlobalExceptionHandler} answers a 422
 * whose body is the full list, so a form can highlight every failing field in one round trip
 * (AC-P0-2.1: a Brand Kit copy rule's failure surfaces with the rule's own {@code message}).
 */
public class CreativeValidationException extends RuntimeException {

    /**
     * @param field    the input field the rule was tested against (e.g. {@code headline})
     * @param ruleId   a stable identifier for the failing rule (e.g. {@code layout}, {@code accentPhrase},
     *                 or a Brand Kit copy rule's own {@code id}) — never a positional index
     * @param message  the failing rule's own message, verbatim
     */
    public record Violation(String field, String ruleId, String message) {
    }

    private final List<Violation> violations;

    public CreativeValidationException(List<Violation> violations) {
        super(summarize(violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    private static String summarize(List<Violation> violations) {
        return violations.stream().map(Violation::message).reduce((a, b) -> a + "; " + b)
                .orElse("Creative failed validation");
    }
}
