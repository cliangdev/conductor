package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared plumbing for every {@code coordinator:*} tool -- JSON-schema builders, argument parsing, and
 * the payload-size clamp all ten tools apply identically. Package-private: every per-context tool class
 * in this package (see {@link CoordinatorAgentTool}) reaches this directly rather than each
 * reimplementing it, and it's independently unit-testable without being exposed outside the package.
 */
final class CoordinatorToolSupport {

    /** Tool-id namespace shared by every class in this package, e.g. {@code "coordinator:list_agents"}. */
    static final String SOURCE_ID = "coordinator";

    /** Same clamp and truncation marker as {@code ConnectorToolProvider} -- a coordinator tool's result
     *  is read by a model, not filed verbatim like a Knowledge Center write, so a byte-sliced result is
     *  an acceptable tradeoff against an unbounded one. */
    private static final int MAX_PAYLOAD_BYTES = 8_000;

    private CoordinatorToolSupport() {
    }

    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    static String stringArg(Object value) {
        return value == null ? null : value.toString();
    }

    static int clampLimit(Object value, int cap) {
        int requested = value instanceof Number n ? n.intValue() : cap;
        if (requested <= 0) return cap;
        return Math.min(requested, cap);
    }

    static ToolResult truncate(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) {
            return ToolResult.ok(json);
        }
        String clipped = new String(bytes, 0, MAX_PAYLOAD_BYTES, StandardCharsets.UTF_8) + "\n…[truncated]";
        return ToolResult.ok(clipped, true);
    }
}
