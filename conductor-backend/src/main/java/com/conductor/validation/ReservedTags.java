package com.conductor.validation;

import java.util.Locale;
import java.util.Set;

/**
 * Tag values Conductor reserves for itself, so a user-supplied tag can never collide with a
 * platform-assigned one. Shared by every surface that accepts a free-text {@code tag}.
 */
public final class ReservedTags {

    public static final Set<String> RESERVED = Set.of("default", "system");

    private ReservedTags() {
    }

    public static boolean isReserved(String tag) {
        return tag != null && RESERVED.contains(tag.trim().toLowerCase(Locale.ROOT));
    }
}
