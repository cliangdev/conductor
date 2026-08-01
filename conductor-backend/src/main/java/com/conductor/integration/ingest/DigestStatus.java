package com.conductor.integration.ingest;

/**
 * Lifecycle of one {@link ConnectorFeedDigest} (a single feed's single period). {@code PENDING} is
 * material and awaiting narration; {@code NARRATING} is claimed by an in-flight narrator run;
 * {@code SUBMITTED} was written to the Knowledge Center; {@code SKIPPED} was found non-material after
 * all (or narration decided nothing was worth writing); {@code DEAD} exhausted its narration retries.
 */
public enum DigestStatus { PENDING, NARRATING, SUBMITTED, SKIPPED, DEAD }
