package com.conductor.signal;

import java.util.UUID;

/**
 * Generates correlation ids that thread a causal chain (webhook receipt -> published {@link Signal}
 * -> {@code knowledge_sources}/{@code workflow_runs} rows it produces) back together for the live
 * pipeline trace view -- see issue #342. Not an identity or idempotency key, purely observability.
 */
public final class TraceIds {

    private TraceIds() {
    }

    public static String newId() {
        return "trc_" + UUID.randomUUID();
    }
}
