package com.conductor.integration.connector.mcp;

import com.conductor.entity.Connection;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.FieldType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generic "Remote MCP server" connector: a URL plus a bearer key for any server speaking MCP over
 * streamable HTTP. It has no data feed of its own; its value is the agent tools the server exposes
 * (see {@link com.conductor.agent.tool.RemoteMcpToolProvider}). Several connections per workspace are
 * allowed, one per server.
 */
@Component
public class RemoteMcpConnector implements FetchConnector {

    public static final String ID = "remote-mcp";

    private final McpClient client;

    public RemoteMcpConnector(McpClient client) {
        this.client = client;
    }

    @Override
    public String getId() { return ID; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata(ID, "Remote MCP server", ConnectorCategory.DEVELOPER,
                "Give agents the tools of any MCP server that speaks streamable HTTP", "MCP");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
                ConnectorConfigField.userInput("url", "Server URL",
                        "The MCP endpoint, e.g. https://example.com/mcp (https only)",
                        FieldType.STRING, true),
                ConnectorConfigField.userInput("apiKey", "API key",
                        "Bearer key for the server. Use a read-only key.",
                        FieldType.SECRET, true)));
    }

    @Override
    public Duration getMaxCacheAge() { return Duration.ofHours(6); }

    /** Result of a connection probe: plain-language message and, on success, the tool count. */
    public record Probe(boolean ok, String message, int toolCount) {}

    /** initialize + tools/list against the connection's server. */
    public Probe probe(ConnectionContext ctx) {
        String url = ctx.configValue("url") == null ? null : ctx.configValue("url").toString();
        if (url == null || url.isBlank()) return new Probe(false, "No server URL is configured.", 0);
        if (ctx.accessToken() == null || ctx.accessToken().isBlank()) {
            return new Probe(false, "No API key is stored for this server.", 0);
        }
        try {
            int count = client.listTools(url, ctx.accessToken()).size();
            return new Probe(true, "Connected. The server offers " + count + (count == 1 ? " tool." : " tools."), count);
        } catch (McpException e) {
            return new Probe(false, e.getMessage(), 0);
        }
    }

    @Override
    public ConnectorData fetchData(ConnectionContext ctx) {
        Probe p = probe(ctx);
        if (!p.ok()) return ConnectorData.degraded(p.message(), Map.of());
        return ConnectorData.healthy(Map.of("toolCount", p.toolCount()));
    }

    @Override
    public ConnectorHealth checkHealth(ConnectionContext ctx) {
        return probe(ctx).ok() ? ConnectorHealth.HEALTHY : ConnectorHealth.DEGRADED;
    }

    /** Refuse a connection that cannot work, with a plain reason (bad URL, rejected key, not an MCP server). */
    @Override
    public void onConnectionCreated(Connection connection, ConnectionContext ctx) {
        Probe p = probe(ctx);
        if (!p.ok()) throw new BusinessException(p.message());
    }
}
