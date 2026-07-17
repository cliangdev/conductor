package com.conductor.knowledge.domain;

/**
 * Lifecycle of a {@link KnowledgeDomain} row. {@code ACTIVE} domains route sources and are shown in the
 * Domains panel; {@code SUGGESTED} is a librarian-raised gap report awaiting admin approval (Phase 3);
 * {@code DISMISSED} is a declined suggestion the librarian should not re-raise for the same slug.
 */
public enum KnowledgeDomainState {
    ACTIVE,
    SUGGESTED,
    DISMISSED
}
