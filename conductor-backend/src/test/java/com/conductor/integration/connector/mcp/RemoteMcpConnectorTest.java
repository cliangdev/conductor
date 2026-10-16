package com.conductor.integration.connector.mcp;

import com.conductor.exception.BusinessException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteMcpConnectorTest {

    private final McpClient client = mock(McpClient.class);
    private final RemoteMcpConnector connector = new RemoteMcpConnector(client);

    private static ConnectionContext ctx(String url, String key) {
        return new ConnectionContext("p", "remote-mcp", "c", key, null, null,
                url == null ? Map.of() : Map.of("url", url), null);
    }

    @Test
    void healthReportsToolCount() {
        when(client.listTools("https://x.example/mcp", "k"))
                .thenReturn(List.of(new McpClient.McpTool("a", null, Map.of()), new McpClient.McpTool("b", null, Map.of())));

        assertThat(connector.checkHealth(ctx("https://x.example/mcp", "k"))).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(connector.probe(ctx("https://x.example/mcp", "k")).message()).contains("2 tools");
        assertThat(connector.fetchData(ctx("https://x.example/mcp", "k")).data()).containsEntry("toolCount", 2);
    }

    @Test
    void rejectedKeyHasPlainMessageAndCreationFails() {
        when(client.listTools("https://x.example/mcp", "bad"))
                .thenThrow(new McpException(McpException.Kind.AUTH, "The server rejected the key."));

        assertThat(connector.checkHealth(ctx("https://x.example/mcp", "bad"))).isEqualTo(ConnectorHealth.DEGRADED);
        assertThatThrownBy(() -> connector.onConnectionCreated(null, ctx("https://x.example/mcp", "bad")))
                .isInstanceOf(BusinessException.class).hasMessage("The server rejected the key.");
    }

    @Test
    void missingUrlOrKeyIsReported() {
        assertThat(connector.probe(ctx(null, "k")).message()).contains("No server URL");
        assertThat(connector.probe(ctx("https://x.example/mcp", null)).message()).contains("No API key");
    }

    @Test
    void declaresUrlAndSecretKeyFields() {
        assertThat(connector.getSpec().fields()).extracting(f -> f.key()).containsExactly("url", "apiKey");
        assertThat(connector.getSpec().singleInstance()).isFalse();
        assertThat(connector.getToolSpec().operations()).isEmpty();
    }
}
