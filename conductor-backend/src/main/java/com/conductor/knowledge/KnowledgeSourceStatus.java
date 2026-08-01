package com.conductor.knowledge;

/**
 * Lifecycle of one ingested {@link KnowledgeSource} row, from arrival through the (later-phase)
 * librarian write-back that turns it into page revisions.
 */
public enum KnowledgeSourceStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    /** Reached the inbox, was read, and was judged not worth a page -- distinct from PROCESSED
     *  (filed) and DEAD (never got a verdict; exhausted retries without ever being looked at). */
    SKIPPED,
    DEAD
}
