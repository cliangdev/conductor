package com.conductor.conversation;

import java.util.List;

/**
 * Thrown by {@link AddressableAgentResolver} when a requested agent name/slug doesn't resolve to
 * exactly one ACTIVE, addressable agent in the project. Plain runtime exception; {@code
 * GlobalExceptionHandler} maps {@link #ambiguous} to 409 and every other case (including {@link
 * #notFound}) to 404 -- see the handler's javadoc for why.
 */
public class AgentNotAddressableException extends RuntimeException {

    private final String attemptedName;
    private final boolean ambiguous;

    private AgentNotAddressableException(String message, String attemptedName, boolean ambiguous) {
        super(message);
        this.attemptedName = attemptedName;
        this.ambiguous = ambiguous;
    }

    /** No ACTIVE, addressable agent matched {@code attemptedName} by slug or name. Also covers the
     *  default-to-CEO case when the seeded CEO agent doesn't exist yet. */
    public static AgentNotAddressableException notFound(String attemptedName) {
        return new AgentNotAddressableException(
                "No addressable agent found for '" + attemptedName + "'", attemptedName, false);
    }

    /** {@code attemptedName} matched more than one addressable agent by display name, with no single
     *  agent whose slug exactly equals it to break the tie. */
    public static AgentNotAddressableException ambiguous(String attemptedName, List<String> matchingSlugs) {
        return new AgentNotAddressableException(
                "'" + attemptedName + "' matches multiple addressable agents by name: " + matchingSlugs
                        + " -- address by slug instead", attemptedName, true);
    }

    public String attemptedName() {
        return attemptedName;
    }

    /** True for {@link #ambiguous}, false for {@link #notFound} -- the discriminator {@code
     *  GlobalExceptionHandler} uses to pick 409 vs 404. */
    public boolean isAmbiguous() {
        return ambiguous;
    }
}
