package com.conductor.service;

import com.conductor.entity.User;

/**
 * Who is acting on a project. Either a human {@link User} (a JWT or user-API-key principal) or a
 * machine actor identified only by a label — a project API key, or a run-scoped MCP token from a
 * workflow container, neither of which has a user behind it.
 *
 * <p>Exactly one of the two is always set, which is what lets a provenance column be recorded as
 * "a user or a label" (the {@code chk_*_attribution} constraints in {@code V103}) and still always
 * render a byline. Resolved by {@link ProjectSecurityService#requireProjectAccess}.
 */
public record ProjectActor(User user, String label) {

    public static ProjectActor of(User user) {
        return new ProjectActor(user, null);
    }

    public static ProjectActor agent(String label) {
        return new ProjectActor(null, label);
    }

    public boolean isMachine() {
        return user == null;
    }

    /** Null for a machine actor — callers comparing authorship must handle that. */
    public String userId() {
        return user == null ? null : user.getId();
    }
}
