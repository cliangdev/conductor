package com.conductor.creative;

import java.util.List;

/**
 * Thrown when a Brand Kit write carries a copy rule whose {@code pattern} or {@code exceptPattern}
 * does not compile as a regex. Answered as a 422 with a {@code fieldErrors} body (the same shape
 * {@code GlobalExceptionHandler} already uses for bean-validation failures), naming which copy rule and
 * which field broke rather than a single opaque message.
 */
public class BrandKitValidationException extends RuntimeException {

    public record FieldError(String field, String message) {
    }

    private final List<FieldError> fieldErrors;

    public BrandKitValidationException(List<FieldError> fieldErrors) {
        super(summarize(fieldErrors));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    private static String summarize(List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(fe -> fe.field() + ": " + fe.message())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Brand Kit failed validation");
    }
}
