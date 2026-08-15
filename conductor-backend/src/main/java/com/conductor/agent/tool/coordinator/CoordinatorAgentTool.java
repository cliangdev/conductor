package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.AgentTool;

/**
 * Shared {@link #id()}/{@link #name()} convention for every {@code coordinator:*} tool: {@code id()} is
 * the namespaced {@code "coordinator:<bareName>"}, {@code name()} is the bare, unnamespaced name the
 * model sees in its tool definitions. Extended by the tool classes nested in each per-context class
 * (e.g. {@code WorkItemCoordinatorTools.CreateWorkItemTool}).
 */
abstract class CoordinatorAgentTool implements AgentTool {

    private final String bareName;

    CoordinatorAgentTool(String bareName) {
        this.bareName = bareName;
    }

    @Override
    public String id() {
        return CoordinatorToolSupport.SOURCE_ID + ":" + bareName;
    }

    @Override
    public String name() {
        return bareName;
    }
}
