package com.conductor.conversation;

import java.util.List;

/**
 * Thrown by {@link AddressableAgentResolver} when a requested agent name/slug doesn't resolve to
 * exactly one ACTIVE, addressable agent in the project. Plain runtime exception; {@code
 * GlobalExceptionHandler} wiring for a proper HTTP mapping lands with the REST API (a later phase).
 */
public class AgentNotAddressableException extends RuntimeException {

    private final String attemptedName;

    private AgentNotAddressableException(String message, String attemptedName) {
        super(message);
        this.attemptedName = attemptedName;
    }

    /** No ACTIVE, addressable agent matched {@code attemptedName} by slug or name. Also covers the
     *  default-to-CEO case when the seeded CEO agent doesn't exist yet. */
    public static AgentNotAddressableException notFound(String attemptedName) {
        return new AgentNotAddressableException(
                "No addressable agent found for '" + attemptedName + "'", attemptedName);
    }

    /** {@code attemptedName} matched more than one addressable agent by display name, with no single
     *  agent whose slug exactly equals it to break the tie. */
    public static AgentNotAddressableException ambiguous(String attemptedName, List<String> matchingSlugs) {
        return new AgentNotAddressableException(
                "'" + attemptedName + "' matches multiple addressable agents by name: " + matchingSlugs
                        + " -- address by slug instead", attemptedName);
    }

    public String attemptedName() {
        return attemptedName;
    }
}
