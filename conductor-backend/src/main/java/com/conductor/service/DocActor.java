package com.conductor.service;

import com.conductor.entity.User;

/**
 * Who is writing to a project doc. Either a human {@link User} (a JWT or user-API-key principal) or a
 * machine actor identified only by a label — a project API key, or a run-scoped MCP token from a
 * workflow container, neither of which has a user behind it.
 *
 * <p>Exactly one of the two is always set, mirroring the {@code chk_*_attribution} CHECK constraints
 * added in {@code V103}, so every doc, version, comment and reply renders a byline.
 */
public record DocActor(User user, String label) {

    public static DocActor of(User user) {
        return new DocActor(user, null);
    }

    public static DocActor agent(String label) {
        return new DocActor(null, label);
    }

    /** Null for a machine actor — callers comparing authorship must handle that. */
    public String userId() {
        return user == null ? null : user.getId();
    }
}
