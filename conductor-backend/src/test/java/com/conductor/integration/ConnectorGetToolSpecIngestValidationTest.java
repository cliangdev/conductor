package com.conductor.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link Connector#getToolSpec()}'s WINDOW-without-{@link IngestConnector} load-time
 * validation: a {@code mode: WINDOW} ingest entry on a connector that only implements
 * {@link FetchConnector} must be dropped (logged at WARN), never silently degraded to
 * {@code SNAPSHOT} — see {@code fixture-window-fetch-only.json} in test resources.
 */
class ConnectorGetToolSpecIngestValidationTest {

    private FetchConnector fetchOnlyConnector() {
        return new FetchConnector() {
            @Override public String getId() { return "fixture-window-fetch-only"; }
            @Override public ConnectorMetadata getMetadata() {
                return new ConnectorMetadata("fixture-window-fetch-only", "Fixture", ConnectorCategory.ANALYTICS, "desc", "F");
            }
            @Override public ConnectorSpec getSpec() { return ConnectorSpec.apiKey(true, List.of()); }
            @Override public ConnectorData fetchData(ConnectionContext c) { return ConnectorData.healthy(java.util.Map.of()); }
            @Override public ConnectorHealth checkHealth(ConnectionContext c) { return ConnectorHealth.HEALTHY; }
        };
    }

    private static class FixtureIngestConnector implements FetchConnector, IngestConnector {
        @Override public String getId() { return "fixture-window-fetch-only"; }
        @Override public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("fixture-window-fetch-only", "Fixture", ConnectorCategory.ANALYTICS, "desc", "F");
        }
        @Override public ConnectorSpec getSpec() { return ConnectorSpec.apiKey(true, List.of()); }
        @Override public ConnectorData fetchData(ConnectionContext c) { return ConnectorData.healthy(java.util.Map.of()); }
        @Override public ConnectorHealth checkHealth(ConnectionContext c) { return ConnectorHealth.HEALTHY; }
        @Override public IngestBatch pull(ConnectionContext ctx, IngestRequest request) { return IngestBatch.empty(); }
    }

    @Test
    void windowFeedIsDroppedForAFetchOnlyConnector() {
        IntegrationToolSpec spec = fetchOnlyConnector().getToolSpec();

        assertThat(spec.ingest()).hasSize(1);
        assertThat(spec.ingest().get(0).id()).isEqualTo("snapshot_feed");
    }

    @Test
    void windowFeedIsKeptWhenConnectorImplementsIngestConnector() {
        IntegrationToolSpec spec = new FixtureIngestConnector().getToolSpec();

        assertThat(spec.ingest()).hasSize(2);
        assertThat(spec.ingest()).extracting(IngestSpec::id)
                .containsExactlyInAnyOrder("window_feed", "snapshot_feed");
    }
}
