package com.conductor.workflow.model;

import java.util.List;

/**
 * A {@code conductor.*} event trigger (today, only {@code conductor.work_item.status_changed}
 * exists). {@code statusFilter} normalizes {@code filters.status} to a list whether the YAML wrote
 * a single string or a list — a workflow fires when the event's status matches ANY entry
 * (case-insensitive), or unconditionally when the list is empty (no filter declared).
 *
 * @param eventType    the event key, e.g. {@code "conductor.work_item.status_changed"}
 * @param statusFilter target statuses to match against, or empty for no filter
 */
public record ConductorEventTrigger(String eventType, List<String> statusFilter) {

    public ConductorEventTrigger {
        statusFilter = Copies.list(statusFilter);
    }
}
