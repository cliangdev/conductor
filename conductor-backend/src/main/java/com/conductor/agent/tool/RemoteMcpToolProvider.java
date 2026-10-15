package com.conductor.agent.tool;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.mcp.McpClient;
import com.conductor.integration.connector.mcp.McpClient.McpTool;
import com.conductor.integration.connector.mcp.McpException;
import com.conductor.integration.connector.mcp.RemoteMcpConnector;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool source {@code "mcp"} — exposes the tools of every ACTIVE {@code remote-mcp} connection in a
 * project to agents on the built-in ReAct loop. Tool id {@code mcp:{connectionId}/{toolName}}; the
 * model-facing name is {@code {slug}_{toolName}} sanitised to {@code ^[a-zA-Z0-9_-]{1,64}$}, where the
 * slug is a short, deterministic piece of the connection id so two servers never collide.
 *
 * <p>Unlike {@link ConnectorToolProvider}, the model's arguments go straight through to the server's
 * {@code tools/call}. Tools only reach a run through the agent's {@code toolIds} allowlist. A server
 * that is down or rejects the key contributes no tools and a WARN line; it never fails the listing.
 * There is no Claude Code equivalent, so {@link #claudeCodeToolName} stays empty and a Claude Code
 * step that asks for one fails fast.
 */
@Component
public class RemoteMcpToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpToolProvider.class);
    static final String SOURCE_ID = "mcp";
    static final int MAX_PAYLOAD_BYTES = 8_000;
    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final McpClient client;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String fingerprint, List<McpTool> tools, Instant expiresAt) {}

    @Autowired
    public RemoteMcpToolProvider(ConnectionRepository connectionRepository,
                                 ConnectionService connectionService,
                                 McpClient client) {
        this(connectionRepository, connectionService, client, Clock.systemUTC());
    }

    RemoteMcpToolProvider(ConnectionRepository connectionRepository, ConnectionService connectionService,
                          McpClient client, Clock clock) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        List<AgentTool> tools = new ArrayList<>();
        for (Connection conn : connectionRepository.findByProjectIdAndConnectorId(projectId, RemoteMcpConnector.ID)) {
            if (!"ACTIVE".equals(conn.getStatus())) continue;
            ConnectionContext ctx = connectionService.toContext(conn);
            String group = groupLabel(conn, ctx);
            for (McpTool t : toolsFor(conn, ctx)) {
                tools.add(new McpAgentTool(conn.getId(), group, t));
            }
        }
        return tools;
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) return Optional.empty();
        String rest = toolId.substring(SOURCE_ID.length() + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return Optional.empty();
        String connectionId = rest.substring(0, slash);
        String toolName = rest.substring(slash + 1);

        Optional<Connection> found = connectionRepository.findById(connectionId)
                .filter(c -> projectId.equals(c.getProjectId()))
                .filter(c -> RemoteMcpConnector.ID.equals(c.getConnectorId()))
                .filter(c -> "ACTIVE".equals(c.getStatus()));
        if (found.isEmpty()) return Optional.empty();
        Connection conn = found.get();
        ConnectionContext ctx = connectionService.toContext(conn);
        return toolsFor(conn, ctx).stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .map(t -> new McpAgentTool(conn.getId(), groupLabel(conn, ctx), t));
    }

    private List<McpTool> toolsFor(Connection conn, ConnectionContext ctx) {
        String url = ctx.configValue("url") == null ? "" : ctx.configValue("url").toString();
        String fingerprint = url + "|" + (ctx.accessToken() == null ? 0 : ctx.accessToken().hashCode());
        Instant now = clock.instant();
        CacheEntry hit = cache.get(conn.getId());
        if (hit != null && hit.fingerprint().equals(fingerprint) && now.isBefore(hit.expiresAt())) {
            return hit.tools();
        }
        try {
            List<McpTool> tools = client.listTools(url, ctx.accessToken());
            cache.put(conn.getId(), new CacheEntry(fingerprint, tools, now.plus(CACHE_TTL)));
            return tools;
        } catch (RuntimeException e) {
            log.warn("Remote MCP tool listing failed for connection={}: {}", conn.getId(), e.getMessage());
            cache.put(conn.getId(), new CacheEntry(fingerprint, List.of(), now.plus(FAILURE_TTL)));
            return List.of();
        }
    }

    private static String groupLabel(Connection conn, ConnectionContext ctx) {
        if (conn.getDisplayLabel() != null && !conn.getDisplayLabel().isBlank()) return conn.getDisplayLabel();
        try {
            Object url = ctx.configValue("url");
            String host = url == null ? null : URI.create(url.toString()).getHost();
            if (host != null) return host;
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "Remote MCP server";
    }

    /** Short deterministic connection slug: the first 6 alphanumerics of the id, lower-cased. */
    static String slug(String connectionId) {
        String s = connectionId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    /** Model-safe name: {@code ^[a-zA-Z0-9_-]{1,64}$}, deterministic, with a hash suffix when clipped. */
    static String modelName(String connectionId, String toolName) {
        String clean = toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String full = slug(connectionId) + "_" + clean;
        if (full.length() <= 64 && clean.equals(toolName)) return full;
        String hash = Integer.toHexString(toolName.hashCode());
        int room = 64 - slug(connectionId).length() - 1 - hash.length() - 1;
        String base = clean.length() > room ? clean.substring(0, room) : clean;
        return slug(connectionId) + "_" + base + "_" + hash;
    }

    private final class McpAgentTool implements AgentTool {
        private final String connectionId;
        private final String group;
        private final McpTool tool;

        private McpAgentTool(String connectionId, String group, McpTool tool) {
            this.connectionId = connectionId;
            this.group = group;
            this.tool = tool;
        }

        @Override
        public String id() {
            return SOURCE_ID + ":" + connectionId + "/" + tool.name();
        }

        @Override
        public String name() {
            return modelName(connectionId, tool.name());
        }

        @Override
        public String description() {
            return tool.description() != null && !tool.description().isBlank()
                    ? tool.description() : "Remote MCP tool " + tool.name();
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return tool.inputSchema();
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            Optional<Connection> found = connectionRepository.findById(connectionId)
                    .filter(c -> c.getProjectId().equals(context.projectId()))
                    .filter(c -> RemoteMcpConnector.ID.equals(c.getConnectorId()))
                    .filter(c -> "ACTIVE".equals(c.getStatus()));
            if (found.isEmpty()) return ToolResult.error("The remote MCP connection is no longer available.");
            try {
                ConnectionContext ctx = connectionService.toContext(found.get());
                Object url = ctx.configValue("url");
                McpClient.CallResult result = client.callTool(
                        url == null ? "" : url.toString(), ctx.accessToken(), tool.name(), arguments);
                if (result.isError()) {
                    return ToolResult.error(clip(result.text().isBlank() ? "The tool reported an error." : result.text()));
                }
                return truncate(result.text());
            } catch (McpException e) {
                log.warn("Remote MCP call failed connection={} tool={}: {}", connectionId, tool.name(), e.getMessage());
                return ToolResult.error(e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Remote MCP call failed connection={} tool={}: {}", connectionId, tool.name(), e.getMessage());
                return ToolResult.error("The remote MCP call failed.");
            }
        }
    }

    private static ToolResult truncate(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) return ToolResult.ok(text);
        String clipped = new String(bytes, 0, MAX_PAYLOAD_BYTES, StandardCharsets.UTF_8) + "\n…[truncated]";
        return ToolResult.ok(clipped, true);
    }

    private static String clip(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) return text;
        return new String(bytes, 0, MAX_PAYLOAD_BYTES, StandardCharsets.UTF_8) + "\n…[truncated]";
    }
}
