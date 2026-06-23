package com.conductor.exception;

/**
 * Signals a 422 Unprocessable Entity — the request was well-formed but failed semantic validation
 * (e.g. publishing a Workflow whose definition has an unreachable status). Distinct from
 * {@link BusinessException} (400) so the COND-18 publish/transition contracts can surface
 * semantic-validation failures with the documented status.
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
