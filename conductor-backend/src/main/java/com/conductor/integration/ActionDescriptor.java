package com.conductor.integration;

import java.util.List;

/**
 * Describes an outbound action a connector exposes. Actively used by {@code WorkflowValidator}
 * (publish-time lint of an {@code action} step's {@code with.action} against the connector's
 * declared actions) and by agent/workflow-authoring surfaces for discovery — see
 * {@link ActionConnector#getActions()}, whose default implementation builds these directly from the
 * connector's tool-spec JSON.
 */
public record ActionDescriptor(String id, String label, List<String> inputKeys) {}
