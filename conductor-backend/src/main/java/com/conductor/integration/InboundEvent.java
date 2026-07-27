package com.conductor.integration;

import java.util.Map;

/** A verified, deduped inbound webhook event handed to a connector's handleEvent. */
public record InboundEvent(String deliveryId, String eventType, String payload, Map<String, String> headers,
                            String traceId) {

    /** Convenience for callers (mostly tests) with no trace id on hand. */
    public InboundEvent(String deliveryId, String eventType, String payload, Map<String, String> headers) {
        this(deliveryId, eventType, payload, headers, null);
    }
}
