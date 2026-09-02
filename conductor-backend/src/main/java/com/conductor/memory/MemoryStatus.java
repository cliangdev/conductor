package com.conductor.memory;

/**
 * Where a memory sits in the consolidation lifecycle. This is <b>not</b> a full lifecycle enum --
 * "superseded" isn't a status value here, it's a derived fact ({@code validTo IS NOT NULL}). A row is
 * live iff its {@code validTo} is null, regardless of which of these two statuses it carries.
 */
public enum MemoryStatus {
    /** Agent-authored, unreviewed extraction -- not yet promoted by consolidation. */
    RAW,
    /** Consolidated/durable -- created directly (manual) or promoted from a RAW row. */
    ACTIVE
}
