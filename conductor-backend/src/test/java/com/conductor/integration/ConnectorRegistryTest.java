package com.conductor.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorRegistryTest {
    @Test
    void registryAutoDiscoversConnectors() {
        IntegrationConnector testConnector = new IntegrationConnector() {
            @Override public String getId() { return "test"; }
            @Override public ConnectorMetadata getMetadata() {
                return new ConnectorMetadata("test", "Test", ConnectorCategory.ANALYTICS, AuthType.API_KEY, "Test connector", "T");
            }
            @Override public List<ConnectorConfigField> getConfigFields() { return List.of(); }
            @Override public ConnectorData fetchData(DecryptedCredentials c) { return ConnectorData.healthy(java.util.Map.of()); }
            @Override public ConnectorHealth checkHealth(DecryptedCredentials c) { return ConnectorHealth.HEALTHY; }
        };

        ConnectorRegistry registry = new ConnectorRegistry(List.of(testConnector));
        registry.init();

        assertThat(registry.getAll()).hasSize(1);
        assertThat(registry.getById("test")).isPresent();
        assertThat(registry.getById("unknown")).isEmpty();
        assertThat(registry.getAllMetadata()).hasSize(1);
        assertThat(registry.getAllMetadata().get(0).id()).isEqualTo("test");
    }
}
