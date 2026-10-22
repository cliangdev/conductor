package com.conductor.integration;

import java.util.Map;

/**
 * The current state of one dimension member (a keyword's bid and status, an ad's serving status): not a daily
 * flow, so it lives beside the daily rows, keyed by the same {@code dimensionKey}.
 */
public record DimensionState(String dimensionKey, Map<String, Object> state) {

    public DimensionState {
        if (state == null) state = Map.of();
    }
}
