package com.conductor.integration;

import java.time.Duration;
import java.util.List;

public interface IntegrationConnector {
    String getId();
    ConnectorMetadata getMetadata();
    List<ConnectorConfigField> getConfigFields();
    ConnectorData fetchData(DecryptedCredentials credentials);
    ConnectorHealth checkHealth(DecryptedCredentials credentials);
    default Duration getMaxCacheAge() { return Duration.ofHours(1); }
}
