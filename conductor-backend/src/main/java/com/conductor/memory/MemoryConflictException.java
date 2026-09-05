package com.conductor.memory;

import com.conductor.exception.ConflictException;

/**
 * Thrown when a write targets a memory whose validity window is already closed ({@code validTo} set) --
 * a closed row is history, not a live document, so it can't be edited or re-closed. Extends the shared
 * {@link ConflictException} rather than introducing a new type so {@code GlobalExceptionHandler}'s
 * existing generic 409 mapping applies with no additional wiring.
 */
public class MemoryConflictException extends ConflictException {

    public MemoryConflictException(String message) {
        super(message);
    }
}
