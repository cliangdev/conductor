package com.conductor.agent;

import java.util.Set;

/**
 * Single source of truth for reserved default-agent slugs -- agents seeded by Conductor itself (e.g.
 * {@code com.conductor.knowledge.KnowledgeWorkflowProvisioner}'s knowledge-librarian) rather than
 * created by a project member. Deleting a default agent is allowed; the owning feature self-heals it
 * back into existence the next time it needs to run. The API layer uses {@link #isDefault(String)} to
 * compute {@code AgentResponse.isDefault} so clients can distinguish the two without a schema column.
 */
public final class DefaultAgentSlugs {

    public static final String KNOWLEDGE_LIBRARIAN = "knowledge-librarian";
    public static final String METRICS_ANALYST = "metrics-analyst";

    public static final Set<String> ALL = Set.of(KNOWLEDGE_LIBRARIAN, METRICS_ANALYST);

    private DefaultAgentSlugs() {
    }

    public static boolean isDefault(String slug) {
        return ALL.contains(slug);
    }
}
