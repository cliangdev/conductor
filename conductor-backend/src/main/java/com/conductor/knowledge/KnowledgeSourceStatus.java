package com.conductor.knowledge;

/**
 * Lifecycle of one ingested {@link KnowledgeSource} row, from arrival through the (later-phase)
 * librarian write-back that turns it into page revisions.
 */
public enum KnowledgeSourceStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    DEAD
}
