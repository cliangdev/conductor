package com.conductor.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorRegistryTest {

    private FetchConnector fetchConnector() {
        return new FetchConnector() {
            @Override public String getId() { return "puller"; }
            @Override public ConnectorMetadata getMetadata() {
                return new ConnectorMetadata("puller", "Puller", ConnectorCategory.ANALYTICS, "desc", "P");
            }
            @Override public ConnectorSpec getSpec() { return ConnectorSpec.apiKey(true, List.of()); }
            @Override public ConnectorData fetchData(ConnectionContext c) { return ConnectorData.healthy(java.util.Map.of()); }
            @Override public ConnectorHealth checkHealth(ConnectionContext c) { return ConnectorHealth.HEALTHY; }
        };
    }

    private WebhookConnector webhookConnector() {
        return new WebhookConnector() {
            @Override public String getId() { return "pusher"; }
            @Override public ConnectorMetadata getMetadata() {
                return new ConnectorMetadata("pusher", "Pusher", ConnectorCategory.DEVELOPER, "desc", "X");
            }
            @Override public ConnectorSpec getSpec() { return ConnectorSpec.webhook(false, List.of()); }
            @Override public WebhookVerification verify(byte[] b, HttpHeaders h, ConnectionContext c) { return WebhookVerification.ok(); }
            @Override public String extractDeliveryId(HttpHeaders h, byte[] b) { return "d"; }
            @Override public String extractEventType(HttpHeaders h, byte[] b) { return "e"; }
            @Override public void handleEvent(InboundEvent e, ConnectionContext c) { }
        };
    }

    @Test
    void capabilityLookupsAreTypeSafe() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(fetchConnector(), webhookConnector()));
        registry.init();

        assertThat(registry.getAll()).hasSize(2);

        // A pull service can see the fetch connector but NOT the webhook-only one.
        assertThat(registry.findFetch("puller")).isPresent();
        assertThat(registry.findFetch("pusher")).isEmpty();

        // The webhook receiver can see the webhook connector but NOT the fetch-only one.
        assertThat(registry.findWebhook("pusher")).isPresent();
        assertThat(registry.findWebhook("puller")).isEmpty();

        assertThat(registry.capabilitiesOf(registry.getById("puller").get()))
                .containsExactly(Capability.FETCH);
        assertThat(registry.capabilitiesOf(registry.getById("pusher").get()))
                .containsExactly(Capability.WEBHOOK);
    }
}
