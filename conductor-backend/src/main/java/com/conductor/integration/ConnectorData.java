package com.conductor.integration;

import java.time.Instant;
import java.util.Map;

public record ConnectorData(Map<String, Object> data, ConnectorHealth healthStatus, Instant fetchedAt, String errorMessage) {
    public static ConnectorData healthy(Map<String, Object> data) {
        return new ConnectorData(data, ConnectorHealth.HEALTHY, Instant.now(), null);
    }
    public static ConnectorData degraded(String errorMessage, Map<String, Object> staleData) {
        return new ConnectorData(staleData, ConnectorHealth.DEGRADED, Instant.now(), errorMessage);
    }
    public static ConnectorData setupRequired(String errorMessage) {
        return new ConnectorData(Map.of(), ConnectorHealth.SETUP_REQUIRED, Instant.now(), errorMessage);
    }
}
