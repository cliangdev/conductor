package com.conductor.exception;

import java.util.List;

/**
 * A Work Item cannot enter a status because fields the Workflow requires there are empty. A 400 like any
 * {@link BusinessException}, plus the missing keys as a {@code missingFields} property so a client can
 * point at the exact inputs rather than parse the message.
 */
public class MissingFieldsException extends BusinessException {

    private final List<String> missingFields;

    public MissingFieldsException(String status, List<String> missingFields) {
        super("Cannot move to " + status + " until these fields are filled in: "
                + String.join(", ", missingFields));
        this.missingFields = List.copyOf(missingFields);
    }

    public List<String> getMissingFields() {
        return missingFields;
    }
}
