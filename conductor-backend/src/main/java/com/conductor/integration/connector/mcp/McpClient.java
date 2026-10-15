package com.conductor.integration.connector.mcp;

import com.conductor.integration.OutboundUrlGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thin MCP client over the streamable HTTP transport (spec 2025-06-18): JSON-RPC 2.0 over POST,
 * answered either as a JSON body or as an SSE stream, with the {@code Mcp-Session-Id} handed out by
 * {@code initialize} echoed on every later request. Written by hand rather than on the official Java
 * SDK to keep full control of timeouts, the SSRF guard, redirects and error wording, and to avoid a
 * second JSON stack alongside Spring Boot 4's.
 *
 * <p>Sessions are cached per (url, key) and re-created once when the server forgets them (HTTP 404).
 * The bearer key is only ever placed in the Authorization header; it is never logged.
 */
@Component
public class McpClient {

    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_BODY_BYTES = 5_000_000;
    private static final int MAX_PAGES = 20;

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mcp-read-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final ObjectMapper mapper;
    private final OutboundUrlGuard guard;
    private final HttpClient http;
    private final Duration callTimeout;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @org.springframework.beans.factory.annotation.Autowired
    public McpClient(ObjectMapper mapper, OutboundUrlGuard guard) {
        this(mapper, guard, Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public McpClient(ObjectMapper mapper, OutboundUrlGuard guard, Duration connectTimeout, Duration callTimeout) {
        this.mapper = mapper;
        this.guard = guard;
        this.callTimeout = callTimeout;
        // Redirects are never followed: a 3xx could bounce the bearer key to an internal address.
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record McpTool(String name, String description, Map<String, Object> inputSchema) {}

    public record CallResult(boolean isError, String text) {}

    /** Initialize (or reuse a session) and list every tool, following {@code nextCursor}. */
    public List<McpTool> listTools(String url, String key) {
        URI uri = validate(url);
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request(uri, key, "tools/list", params);
            JsonNode list = result.path("tools");
            if (list.isArray()) {
                for (JsonNode t : list) {
                    String name = t.path("name").asText("");
                    if (name.isBlank()) continue;
                    Map<String, Object> schema = t.hasNonNull("inputSchema")
                            ? mapper.convertValue(t.get("inputSchema"), new TypeReference<Map<String, Object>>() {})
                            : Map.of("type", "object", "properties", Map.of());
                    tools.add(new McpTool(name, t.path("description").asText(null), schema));
                }
            }
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
            if (cursor == null || cursor.isBlank()) break;
        }
        return tools;
    }

    /** Call one tool; the model's arguments are passed through untouched. */
    public CallResult callTool(String url, String key, String toolName, Map<String, Object> arguments) {
        URI uri = validate(url);
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = request(uri, key, "tools/call", params);
        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (text.length() > 0) text.append('\n');
                String type = part.path("type").asText("");
                if (type.equals("text")) {
                    text.append(part.path("text").asText(""));
                } else if (type.equals("resource") && part.path("resource").hasNonNull("text")) {
                    text.append(part.path("resource").path("text").asText());
                } else {
                    text.append('[').append(type.isEmpty() ? "unknown" : type).append(" content omitted]");
                }
            }
        }
        if (text.length() == 0 && result.hasNonNull("structuredContent")) {
            text.append(result.get("structuredContent").toString());
        }
        return new CallResult(isError, text.toString());
    }

    /** Drop cached sessions (tests). */
    public void clearSessions() {
        sessions.clear();
    }

    private URI validate(String url) {
        try {
            return guard.validate(url);
        } catch (IllegalArgumentException e) {
            throw new McpException(McpException.Kind.UNREACHABLE, e.getMessage());
        }
    }

    // ---- JSON-RPC plumbing ----

    private JsonNode request(URI uri, String key, String method, ObjectNode params) {
        String cacheKey = uri + "|" + fingerprint(key);
        for (int attempt = 0; ; attempt++) {
            Session session = sessions.computeIfAbsent(cacheKey, k -> new Session());
            try {
                session.ensureInitialized(uri, key);
                return rpcResult(post(uri, key, session.id, rpcBody(method, params, true)));
            } catch (SessionExpired e) {
                sessions.remove(cacheKey, session);
                if (attempt >= 1) {
                    throw new McpException(McpException.Kind.SERVER, "The server keeps dropping the session.");
                }
            }
        }
    }

    private final class Session {
        private volatile boolean initialized;
        private volatile String id;

        synchronized void ensureInitialized(URI uri, String key) {
            if (initialized) return;
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode info = params.putObject("clientInfo");
            info.put("name", "conductor");
            info.put("version", "1.0");
            Exchange ex = post(uri, key, null, rpcBody("initialize", params, true));
            JsonNode result = rpcResult(ex);
            if (!result.has("protocolVersion") && !result.has("capabilities") && !result.has("serverInfo")) {
                throw notMcp();
            }
            this.id = ex.sessionId;
            post(uri, key, this.id, rpcBody("notifications/initialized", mapper.createObjectNode(), false));
            this.initialized = true;
        }
    }

    private static final class SessionExpired extends RuntimeException {
        SessionExpired() {
            super(null, null, false, false);
        }
    }

    private record Exchange(String contentType, String body, String sessionId, long requestId) {}

    private ObjectNode rpcBody(String method, ObjectNode params, boolean withId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (withId) n.put("id", ids.getAndIncrement());
        n.put("method", method);
        n.set("params", params);
        return n;
    }

    private JsonNode rpcResult(Exchange ex) {
        JsonNode msg = extractResponse(ex);
        if (msg.hasNonNull("error")) {
            String m = msg.path("error").path("message").asText("unknown error");
            throw new McpException(McpException.Kind.SERVER, "The server returned an error: " + m);
        }
        if (!msg.has("result")) throw notMcp();
        return msg.get("result");
    }

    private Exchange post(URI uri, String key, String sessionId, ObjectNode body) {
        long requestId = body.path("id").asLong(-1);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.trim());
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
            b.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw new McpException(McpException.Kind.UNREACHABLE, "Could not reach the server: the connection timed out.");
        } catch (HttpTimeoutException e) {
            throw new McpException(McpException.Kind.TIMEOUT, "The server took too long to respond.");
        } catch (IOException e) {
            throw new McpException(McpException.Kind.UNREACHABLE, "Could not reach the server.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(McpException.Kind.UNREACHABLE, "The request was interrupted.");
        }
        int status = resp.statusCode();
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        boolean sse = contentType.toLowerCase().contains("text/event-stream");
        InputStream in = resp.body();
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        // The request timeout only covers the response headers; this bounds the body read too (an SSE
        // stream may stay open after the answer has been sent).
        java.util.concurrent.ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try { in.close(); } catch (IOException ignored) { }
        }, callTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        String text;
        try {
            text = readCapped(in, sse, requestId);
        } catch (IOException e) {
            if (timedOut.get() || e instanceof HttpTimeoutException) {
                throw new McpException(McpException.Kind.TIMEOUT, "The server took too long to respond.");
            }
            throw new McpException(McpException.Kind.UNREACHABLE, "The connection to the server was lost.");
        } finally {
            watchdog.cancel(false);
            try { in.close(); } catch (IOException ignored) { }
        }
        if (status == 401 || status == 403) {
            throw new McpException(McpException.Kind.AUTH, "The server rejected the key.");
        }
        if (status == 404 && sessionId != null) throw new SessionExpired();
        if (status >= 400) {
            throw new McpException(McpException.Kind.SERVER, "The server returned HTTP " + status + ".");
        }
        String sid = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        return new Exchange(resp.headers().firstValue("Content-Type").orElse(""), text, sid, requestId);
    }

    private String readCapped(InputStream in, boolean sse, long requestId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                throw new McpException(McpException.Kind.SERVER, "The server's response was too large.");
            }
            if (sse && requestId >= 0 && hasResponseEvent(out.toString(StandardCharsets.UTF_8), requestId)) break;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private boolean hasResponseEvent(String body, long requestId) {
        // Only complete events (terminated by a blank line) count.
        int end = Math.max(body.lastIndexOf("\n\n"), body.lastIndexOf("\r\n\r\n"));
        if (end < 0) return false;
        for (String data : sseEvents(body.substring(0, end + 2))) {
            try {
                JsonNode node = mapper.readTree(data);
                if (node != null && node.has("id") && node.get("id").asLong(-2) == requestId
                        && (node.has("result") || node.has("error"))) {
                    return true;
                }
            } catch (Exception ignored) {
                // not JSON; keep reading
            }
        }
        return false;
    }

    /** The JSON-RPC message answering this request, from a JSON body or an SSE stream. */
    private JsonNode extractResponse(Exchange ex) {
        String ct = ex.contentType.toLowerCase();
        try {
            if (ct.contains("text/event-stream")) {
                JsonNode fallback = null;
                for (String data : sseEvents(ex.body)) {
                    JsonNode node = mapper.readTree(data);
                    if (node == null || !node.has("jsonrpc") || !(node.has("result") || node.has("error"))) continue;
                    if (node.has("id") && node.get("id").asLong(-2) == ex.requestId) return node;
                    if (fallback == null) fallback = node;
                }
                if (fallback != null) return fallback;
            } else if (ct.contains("json")) {
                JsonNode node = mapper.readTree(ex.body);
                if (node != null && node.isArray()) {
                    for (JsonNode n : node) {
                        if (n.has("id") && n.get("id").asLong(-2) == ex.requestId) return n;
                    }
                } else if (node != null && node.has("jsonrpc")) {
                    return node;
                }
            }
        } catch (Exception e) {
            // fall through to not-MCP
        }
        throw notMcp();
    }

    static List<String> sseEvents(String body) {
        List<String> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (data.length() > 0) events.add(data.toString());
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String v = line.substring(5);
                data.append(v.startsWith(" ") ? v.substring(1) : v);
            }
        }
        if (data.length() > 0) events.add(data.toString());
        return events;
    }

    private static McpException notMcp() {
        return new McpException(McpException.Kind.NOT_MCP, "That URL did not respond like an MCP server.");
    }

    private static String fingerprint(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }
}
