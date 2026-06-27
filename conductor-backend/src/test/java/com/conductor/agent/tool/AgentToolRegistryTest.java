package com.conductor.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test for namespace-based routing across pluggable tool sources. */
class AgentToolRegistryTest {

    private AgentTool tool(String id) {
        return new AgentTool() {
            public String id() { return id; }
            public String name() { return id; }
            public String description() { return "d"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public ToolResult invoke(Map<String, Object> args, ToolInvocationContext ctx) {
                return ToolResult.ok("ran:" + id);
            }
        };
    }

    private AgentToolProvider provider(String source, AgentTool... tools) {
        return new AgentToolProvider() {
            public String sourceId() { return source; }
            public List<AgentTool> available(String projectId) { return List.of(tools); }
            public Optional<AgentTool> resolve(String projectId, String toolId) {
                for (AgentTool t : tools) if (t.id().equals(toolId)) return Optional.of(t);
                return Optional.empty();
            }
        };
    }

    private AgentToolRegistry registryWith(AgentToolProvider... providers) {
        AgentToolRegistry r = new AgentToolRegistry(List.of(providers));
        r.init();
        return r;
    }

    @Test
    void routesResolutionToOwningSourceByPrefix() {
        AgentToolRegistry registry = registryWith(
                provider("connector", tool("connector:posthog/summary")),
                provider("http", tool("http:notion-search")));

        assertThat(registry.resolve("p1", "connector:posthog/summary")).isPresent();
        assertThat(registry.resolve("p1", "http:notion-search")).isPresent();
        assertThat(registry.resolve("p1", "builtin:unknown")).isEmpty();
        assertThat(registry.resolve("p1", "no-namespace")).isEmpty();
    }

    @Test
    void aggregatesAvailableToolsAndResolvesMixedSources() {
        AgentToolRegistry registry = registryWith(
                provider("connector", tool("connector:gsc/search")),
                provider("http", tool("http:webhook")));

        assertThat(registry.availableTools("p1")).hasSize(2);

        List<AgentTool> resolved = registry.resolveAll("p1",
                List.of("connector:gsc/search", "http:webhook", "http:missing"));
        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).invoke(Map.of(), new ToolInvocationContext("p1", "a1", "r1")).payload())
                .isEqualTo("ran:connector:gsc/search");
    }
}
