package com.conductor.agent.tool;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.OutboundUrlGuard;
import com.conductor.integration.connector.mcp.McpClient;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteMcpToolProviderTest {

    private static final String PROJECT = "proj-1";
    private static final String CONN = "c0ffee11-aaaa-bbbb-cccc-000000000001";
    private static final String INIT =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}";
    private static final String TOOLS =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                    + "{\"name\":\"get_users\",\"description\":\"List users\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}},"
                    + "{\"name\":\"delete.user\",\"description\":\"Dangerous\",\"inputSchema\":{\"type\":\"object\"}}]}}";

    private WireMockServer server;
    private ConnectionRepository repo;
    private ConnectionService connectionService;
    private RemoteMcpToolProvider provider;
    private Connection conn;
    private String url;
    private MutableClock clock;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        url = "http://localhost:" + server.port() + "/mcp";
        server.stubFor(post("/mcp").withRequestBody(containing("\"initialize\""))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(INIT)));
        server.stubFor(post("/mcp").withRequestBody(containing("notifications/initialized"))
                .willReturn(aResponse().withStatus(202)));
        server.stubFor(post("/mcp").withRequestBody(containing("tools/list"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(TOOLS)));

        conn = new Connection();
        conn.setId(CONN);
        conn.setProjectId(PROJECT);
        conn.setConnectorId("remote-mcp");
        conn.setStatus("ACTIVE");
        conn.setDisplayLabel("Admin server");

        repo = mock(ConnectionRepository.class);
        when(repo.findByProjectIdAndConnectorId(PROJECT, "remote-mcp")).thenReturn(List.of(conn));
        when(repo.findById(CONN)).thenReturn(Optional.of(conn));
        connectionService = mock(ConnectionService.class);
        when(connectionService.toContext(any())).thenAnswer(inv -> new ConnectionContext(
                PROJECT, "remote-mcp", CONN, "the-key", null, null, Map.of("url", url), null));

        clock = new MutableClock();
        McpClient client = new McpClient(new ObjectMapper(), new OutboundUrlGuard(true),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        provider = new RemoteMcpToolProvider(repo, connectionService, client, clock);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void listsToolsGroupedByConnectionWithSafeUniqueNames() {
        List<AgentTool> tools = provider.available(PROJECT);

        assertThat(tools).hasSize(2);
        assertThat(tools).allMatch(t -> t.group().equals("Admin server"));
        assertThat(tools.get(0).id()).isEqualTo("mcp:" + CONN + "/get_users");
        assertThat(tools.get(0).name()).isEqualTo("c0ffee_get_users");
        assertThat(tools.get(0).inputSchema()).containsKey("properties");
        assertThat(tools).allMatch(t -> Pattern.matches("^[a-zA-Z0-9_-]{1,64}$", t.name()));
        // A name that needed sanitising stays distinct and deterministic.
        assertThat(tools.get(1).name()).startsWith("c0ffee_delete_user_");
        assertThat(provider.available(PROJECT).get(1).name()).isEqualTo(tools.get(1).name());
    }

    @Test
    void longToolNamesAreClippedToSixtyFourCharacters() {
        String name = RemoteMcpToolProvider.modelName(CONN, "x".repeat(200));
        assertThat(name).hasSizeLessThanOrEqualTo(64).matches("^[a-zA-Z0-9_-]{1,64}$");
    }

    @Test
    void cachesToolListForTenMinutes() {
        provider.available(PROJECT);
        provider.available(PROJECT);
        server.verify(1, postRequestedFor(urlEqualTo("/mcp")).withRequestBody(containing("tools/list")));

        clock.now = clock.now.plus(Duration.ofMinutes(11));
        provider.available(PROJECT);
        server.verify(2, postRequestedFor(urlEqualTo("/mcp")).withRequestBody(containing("tools/list")));
    }

    @Test
    void failingServerYieldsNoToolsAndNoException() {
        server.stubFor(post("/mcp").atPriority(1).willReturn(aResponse().withStatus(401)));

        assertThat(provider.available(PROJECT)).isEmpty();
    }

    @Test
    void inactiveConnectionIsIgnored() {
        conn.setStatus("DISABLED");
        assertThat(provider.available(PROJECT)).isEmpty();
        assertThat(provider.resolve(PROJECT, "mcp:" + CONN + "/get_users")).isEmpty();
    }

    @Test
    void onlyAllowlistedToolsReachTheRun() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(provider));
        registry.init();

        List<AgentTool> forRun = registry.resolveAll(PROJECT, List.of("mcp:" + CONN + "/get_users"));

        assertThat(forRun).extracting(AgentTool::id).containsExactly("mcp:" + CONN + "/get_users");
        assertThat(registry.resolve(PROJECT, "mcp:" + CONN + "/does_not_exist")).isEmpty();
        assertThat(registry.resolve("other-project", "mcp:" + CONN + "/get_users")).isEmpty();
    }

    @Test
    void argumentsPassThroughAndTextIsReturned() {
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"alice\"},{\"type\":\"text\",\"text\":\"bob\"}]}}")));
        AgentTool tool = provider.resolve(PROJECT, "mcp:" + CONN + "/get_users").orElseThrow();

        ToolResult r = tool.invoke(Map.of("q", "al", "limit", 5), new ToolInvocationContext(PROJECT, "a", "run"));

        assertThat(r.ok()).isTrue();
        assertThat(r.payload()).isEqualTo("alice\nbob");
        server.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(containing("\"name\":\"get_users\""))
                .withRequestBody(containing("\"q\":\"al\""))
                .withRequestBody(containing("\"limit\":5"))
                .withHeader("Authorization", equalTo("Bearer the-key")));
    }

    @Test
    void errorResultBecomesNotOkWithServerText() {
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"nope\"}]}}")));
        AgentTool tool = provider.resolve(PROJECT, "mcp:" + CONN + "/get_users").orElseThrow();

        ToolResult r = tool.invoke(Map.of(), new ToolInvocationContext(PROJECT, "a", "run"));

        assertThat(r.ok()).isFalse();
        assertThat(r.payload()).isEqualTo("nope");
    }

    @Test
    void largeResultsAreTruncatedAtEightThousandBytes() {
        String big = "y".repeat(20_000);
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + big + "\"}]}}")));
        AgentTool tool = provider.resolve(PROJECT, "mcp:" + CONN + "/get_users").orElseThrow();

        ToolResult r = tool.invoke(Map.of(), new ToolInvocationContext(PROJECT, "a", "run"));

        assertThat(r.ok()).isTrue();
        assertThat(r.truncated()).isTrue();
        assertThat(r.payload().length()).isLessThan(8_100);
    }

    @Test
    void callFailureIsAToolErrorNotAnException() {
        AgentTool tool = provider.resolve(PROJECT, "mcp:" + CONN + "/get_users").orElseThrow();
        server.stubFor(post("/mcp").atPriority(1).willReturn(aResponse().withStatus(401)));
        // New session needed: drop the cached one by using a different key via fresh client state.
        ToolResult r = tool.invoke(Map.of(), new ToolInvocationContext(PROJECT, "a", "run"));

        assertThat(r.ok()).isFalse();
        assertThat(r.payload()).isNotBlank();
    }

    @Test
    void invokeRefusesAConnectionFromAnotherProject() {
        AgentTool tool = provider.resolve(PROJECT, "mcp:" + CONN + "/get_users").orElseThrow();

        ToolResult r = tool.invoke(Map.of(), new ToolInvocationContext("someone-else", "a", "run"));

        assertThat(r.ok()).isFalse();
    }

    @Test
    void hasNoClaudeCodeEquivalent() {
        assertThat(provider.claudeCodeToolName("mcp:" + CONN + "/get_users")).isEmpty();
    }
}
