package com.conductor.entity;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Conversions for a member's role set: the {@code varchar[]} column form, the legacy single {@code role}
 * column, and the effective set (ADMIN implies every role).
 */
public final class MemberRoles {

    private MemberRoles() {}

    /** The legacy single-role value for a set: ADMIN if held, else CREATOR, else REVIEWER (also for empty). */
    public static MemberRole primary(Collection<MemberRole> roles) {
        if (roles != null) {
            if (roles.contains(MemberRole.ADMIN)) return MemberRole.ADMIN;
            if (roles.contains(MemberRole.CREATOR)) return MemberRole.CREATOR;
        }
        return MemberRole.REVIEWER;
    }

    /** ADMIN implies CREATOR and REVIEWER. */
    public static Set<MemberRole> effective(Collection<MemberRole> stored) {
        if (stored == null || stored.isEmpty()) {
            return EnumSet.noneOf(MemberRole.class);
        }
        EnumSet<MemberRole> set = EnumSet.copyOf(stored);
        if (set.contains(MemberRole.ADMIN)) {
            return EnumSet.allOf(MemberRole.class);
        }
        return set;
    }

    static String[] toStored(Collection<MemberRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return new String[0];
        }
        return EnumSet.copyOf(roles).stream().map(Enum::name).toArray(String[]::new);
    }

    static Set<MemberRole> fromStored(String[] stored) {
        EnumSet<MemberRole> set = EnumSet.noneOf(MemberRole.class);
        // NULL (a row old code wrote before the sync trigger filled it) reads as the empty set.
        if (stored != null) {
            Arrays.stream(stored).map(MemberRole::valueOf).forEach(set::add);
        }
        return set;
    }
}
