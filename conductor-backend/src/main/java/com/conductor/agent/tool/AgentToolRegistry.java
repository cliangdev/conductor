package com.conductor.agent.tool;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates every {@link AgentToolProvider} (Spring injects the list) and routes by the tool-id
 * namespace ({@code "<sourceId>:..."}). Exposes the union of available tools for agent authoring and
 * resolves an agent's bound tool ids to live {@link AgentTool}s at run time. The runner depends only
 * on this registry, never on individual sources — adding a source changes nothing downstream.
 */
@Component
public class AgentToolRegistry {

    private final List<AgentToolProvider> providers;
    private Map<String, AgentToolProvider> bySource;

    public AgentToolRegistry(List<AgentToolProvider> providers) {
        this.providers = providers;
    }

    @PostConstruct
    public void init() {
        Map<String, AgentToolProvider> map = new LinkedHashMap<>();
        for (AgentToolProvider p : providers) {
            map.put(p.sourceId(), p);
        }
        this.bySource = Collections.unmodifiableMap(map);
    }

    /** A tool offered to a project, tagged with its canonical source id (the owning provider's). */
    public record SourcedTool(AgentTool tool, String source) {}

    /** Every tool offered to a project across all sources — for agent authoring/discovery. */
    public List<AgentTool> availableTools(String projectId) {
        List<AgentTool> all = new ArrayList<>();
        for (AgentToolProvider p : bySource.values()) {
            all.addAll(p.available(projectId));
        }
        return all;
    }

    /**
     * Every available tool paired with the canonical {@code sourceId} of the provider that owns it —
     * taken from the registry's source map, never re-parsed from the id string. For read/discovery
     * surfaces (e.g. the Agents UI tool picker) that need to group tools by source.
     */
    public List<SourcedTool> availableToolsWithSource(String projectId) {
        List<SourcedTool> all = new ArrayList<>();
        bySource.forEach((source, provider) -> {
            for (AgentTool t : provider.available(projectId)) {
                all.add(new SourcedTool(t, source));
            }
        });
        return all;
    }

    /** Resolve one namespaced tool id to a live tool, routing to its owning source. */
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        String sourceId = sourceOf(toolId);
        if (sourceId == null) return Optional.empty();
        AgentToolProvider provider = bySource.get(sourceId);
        return provider == null ? Optional.empty() : provider.resolve(projectId, toolId);
    }

    /** Resolve an agent's bound tool ids to live tools, skipping any that no longer resolve. */
    public List<AgentTool> resolveAll(String projectId, List<String> toolIds) {
        List<AgentTool> tools = new ArrayList<>();
        if (toolIds == null) return tools;
        for (String id : toolIds) {
            resolve(projectId, id).ifPresent(tools::add);
        }
        return tools;
    }

    /**
     * The Claude Code {@code --allowedTools} name for a namespaced tool id, routed to its owning
     * source the same way {@link #resolve} is. Empty when the id's source is unknown, or the owning
     * source has no Claude Code equivalent for it (see {@link AgentToolProvider#claudeCodeToolName}).
     */
    public Optional<String> claudeCodeToolName(String toolId) {
        String sourceId = sourceOf(toolId);
        if (sourceId == null) return Optional.empty();
        AgentToolProvider provider = bySource.get(sourceId);
        return provider == null ? Optional.empty() : provider.claudeCodeToolName(toolId);
    }

    private String sourceOf(String toolId) {
        if (toolId == null) return null;
        int idx = toolId.indexOf(':');
        return idx <= 0 ? null : toolId.substring(0, idx);
    }
}
