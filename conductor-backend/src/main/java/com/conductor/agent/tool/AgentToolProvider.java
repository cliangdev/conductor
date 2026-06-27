package com.conductor.agent.tool;

import java.util.List;
import java.util.Optional;

/**
 * SPI for a tool <em>source</em>. Each implementation contributes {@link AgentTool}s from one
 * origin — connectors, user-defined HTTP tools, built-in actions, MCP servers — and is auto-
 * discovered by {@link AgentToolRegistry}, exactly as connectors are discovered by
 * {@code ConnectorRegistry}. Adding a new kind of tool is one bean; the runner never changes.
 *
 * <p>Tool ids this provider owns are prefixed {@code "<sourceId>:"}; the registry routes by that
 * prefix. Implementations are project-scoped at call time (a project's connections/HTTP-tool defs
 * determine what is available).
 */
public interface AgentToolProvider {

    /** Stable source id used as the tool-id namespace, e.g. {@code "connector"}, {@code "http"}. */
    String sourceId();

    /** All tools this source offers for the given project (for agent authoring/discovery). */
    List<AgentTool> available(String projectId);

    /** Resolve one tool by its namespaced id for the given project, if this source owns it. */
    Optional<AgentTool> resolve(String projectId, String toolId);
}
