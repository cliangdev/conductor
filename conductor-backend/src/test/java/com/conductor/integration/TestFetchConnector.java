package com.conductor.integration;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Lightweight stub connector for integration tests. Returns deterministic data. */
@Component
@Profile("test")
public class TestFetchConnector implements FetchConnector {

    @Override
    public String getId() { return "test-data-source"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(
            "test-data-source", "Test Data Source", ConnectorCategory.ANALYTICS,
            "Test connector for E2E tests", "TD");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("apiKey", "API Key", "Test key", FieldType.SECRET, true)
        ));
    }

    @Override
    public IntegrationToolSpec getToolSpec() {
        return new IntegrationToolSpec(
            "Test data source for integration tests",
            List.of(
                new ToolOperation("fetch_test_data", "Returns fixed test data",
                    Map.of(), "{value:integer}", List.of("value"))
            )
        );
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        return ConnectorData.healthy(Map.of("value", 42));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return ConnectorHealth.HEALTHY;
    }
}
