package com.conductor.integration.connector;

import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.connector.local.LocalGcpBillingConnector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GcpBillingConnectorTest {

    @Test
    void localConnectorReturnsFixtureCostBreakdown() {
        LocalGcpBillingConnector connector = new LocalGcpBillingConnector();

        ConnectorData result = connector.fetchData(
                new DecryptedCredentials("token", null, null, Map.of()));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.HEALTHY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) result.data().get("services");
        assertThat(services).hasSize(5);
        assertThat(result.data().get("totalCost")).isEqualTo(1240.52);
        assertThat(result.data().get("currency")).isEqualTo("USD");
    }

    @Test
    void prodConnectorWithMissingDatasetReturnsSetupRequired() {
        GcpBillingConnector connector = new GcpBillingConnector();

        ConnectorData result = connector.fetchData(
                new DecryptedCredentials("token", null, null, Map.of()));

        assertThat(result.healthStatus()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }
}
