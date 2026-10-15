package com.conductor.integration.connector.mcp;

import com.conductor.integration.OutboundUrlGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private static final String INIT_RESULT =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}}";

    private WireMockServer server;
    private McpClient client;
    private String url;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        url = "http://localhost:" + server.port() + "/mcp";
        client = new McpClient(new ObjectMapper(), new OutboundUrlGuard(true), Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private void stubInitialize(String sessionId) {
        var resp = aResponse().withHeader("Content-Type", "application/json").withBody(INIT_RESULT);
        if (sessionId != null) resp.withHeader("Mcp-Session-Id", sessionId);
        server.stubFor(post("/mcp").withRequestBody(containing("\"initialize\"")).willReturn(resp));
        server.stubFor(post("/mcp").withRequestBody(containing("notifications/initialized"))
                .willReturn(aResponse().withStatus(202)));
    }

    @Test
    void listsToolsFromJsonBodyAndSendsKeyAndSessionId() {
        stubInitialize("sess-1");
        server.stubFor(post("/mcp").withRequestBody(containing("tools/list"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"get_users\",\"description\":\"d\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]}}")));

        List<McpClient.McpTool> tools = client.listTools(url, "secret-key");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("get_users");
        assertThat(tools.get(0).inputSchema()).containsKey("properties");
        server.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withHeader("Authorization", equalTo("Bearer secret-key"))
                .withHeader("Accept", containing("text/event-stream"))
                .withHeader("Mcp-Session-Id", equalTo("sess-1"))
                .withRequestBody(containing("tools/list")));
        server.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withHeader("Mcp-Session-Id", equalTo("sess-1"))
                .withRequestBody(containing("notifications/initialized")));
        server.verify(postRequestedFor(urlEqualTo("/mcp"))
                .withRequestBody(containing("\"protocolVersion\":\"2025-06-18\""))
                .withRequestBody(containing("\"name\":\"conductor\"")));
    }

    @Test
    void readsSseResponseAndIgnoresOtherEvents() {
        stubInitialize(null);
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call"))
                .willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                                + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}\n\n")));

        McpClient.CallResult r = client.callTool(url, "k", "t", Map.of("a", 1));

        assertThat(r.isError()).isFalse();
        assertThat(r.text()).isEqualTo("hello");
        server.verify(postRequestedFor(urlEqualTo("/mcp")).withRequestBody(containing("\"arguments\":{\"a\":1}")));
    }

    @Test
    void followsPagination() {
        stubInitialize(null);
        server.stubFor(post("/mcp").withRequestBody(containing("tools/list")).withRequestBody(notContaining("cursor"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"a\"}],\"nextCursor\":\"c2\"}}")));
        server.stubFor(post("/mcp").withRequestBody(containing("tools/list")).withRequestBody(containing("\"cursor\":\"c2\""))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"tools\":[{\"name\":\"b\"}]}}")));

        assertThat(client.listTools(url, "k")).extracting(McpClient.McpTool::name).containsExactly("a", "b");
    }

    @Test
    void mapsIsErrorResult() {
        stubInitialize(null);
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"bad input\"}]}}")));

        McpClient.CallResult r = client.callTool(url, "k", "t", Map.of());

        assertThat(r.isError()).isTrue();
        assertThat(r.text()).isEqualTo("bad input");
    }

    @Test
    void reinitializesOnceWhenSessionExpires() {
        stubInitialize("sess-1");
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call")).inScenario("expiry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"one\"}]}}"))
                .willSetStateTo("expired"));
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call")).inScenario("expiry")
                .whenScenarioStateIs("expired")
                .willReturn(aResponse().withStatus(404)).willSetStateTo("recovered"));
        server.stubFor(post("/mcp").withRequestBody(containing("tools/call")).inScenario("expiry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"two\"}]}}")));

        assertThat(client.callTool(url, "k", "t", Map.of()).text()).isEqualTo("one");
        assertThat(client.callTool(url, "k", "t", Map.of()).text()).isEqualTo("two");
        server.verify(2, postRequestedFor(urlEqualTo("/mcp")).withRequestBody(containing("\"initialize\"")));
    }

    @Test
    void unauthorizedGivesPlainMessage() {
        server.stubFor(post("/mcp").willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.listTools(url, "bad"))
                .isInstanceOf(McpException.class)
                .hasMessage("The server rejected the key.");
    }

    @Test
    void nonMcpResponseIsReported() {
        server.stubFor(post("/mcp").willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html/>")));

        assertThatThrownBy(() -> client.listTools(url, "k"))
                .isInstanceOf(McpException.class).hasMessageContaining("web page, not an MCP response");
    }

    @Test
    void wrongPathStatusesGetPlainMessages() {
        server.stubFor(post("/mcp").willReturn(aResponse().withStatus(405)));
        assertThatThrownBy(() -> client.listTools(url, "k")).isInstanceOf(McpException.class)
                .hasMessage("That URL didn't answer as an MCP server (HTTP 405). Use the server's MCP endpoint, often ending in /mcp.");
        server.resetAll();
        server.stubFor(post("/mcp").willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client.listTools(url, "k")).hasMessageContaining("(HTTP 404)");
        server.resetAll();
        server.stubFor(post("/mcp").willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client.listTools(url, "k")).hasMessageContaining("internal error (HTTP 503)");
        server.resetAll();
        server.stubFor(post("/mcp").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"a\":1}")));
        assertThatThrownBy(() -> client.listTools(url, "k")).hasMessageContaining("didn't answer as an MCP server");
    }

    @Test
    void slowServerTimesOut() {
        server.stubFor(post("/mcp").willReturn(aResponse().withFixedDelay(2500)
                .withHeader("Content-Type", "application/json").withBody(INIT_RESULT)));

        assertThatThrownBy(() -> client.listTools(url, "k"))
                .isInstanceOf(McpException.class).hasMessageContaining("too long");
    }

    @Test
    void unreachableHostIsReported() {
        server.stop();
        assertThatThrownBy(() -> client.listTools(url, "k"))
                .isInstanceOf(McpException.class).hasMessageContaining("Could not reach");
    }

    @Test
    void strictGuardBlocksBeforeAnyRequest() {
        McpClient strict = new McpClient(new ObjectMapper(), new OutboundUrlGuard(false));
        assertThatThrownBy(() -> strict.listTools("http://169.254.169.254/mcp", "k"))
                .isInstanceOf(McpException.class).hasMessageContaining("https://");
        assertThat(server.getAllServeEvents()).isEmpty();
    }
}
