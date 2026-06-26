package com.conductor.integration;

import java.util.List;

/** Describes a connected integration as a workflow data-source tool. Stored as tool_metadata JSON on the connection row. */
public record IntegrationToolSpec(String description, List<ToolOperation> operations) {}
