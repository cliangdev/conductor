package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Statecharts by {@code workflow@version}, resolved once each, for the length of one request: a list or tree
 * read touches many items but only a handful of distinct Workflows.
 *
 * <p>The one rule shared by every reader: a statechart that cannot be resolved (a Workflow that was never
 * seeded in the project, a pinned version that is gone) reads as <em>open</em> -- not terminal, no known
 * category -- so a broken Workflow never fails or hides a whole page. Callers that need the failure resolve
 * through {@link WorkItemWorkflowService} directly.
 *
 * <p>Not thread-safe and not shared: build one per request, for one project.
 */
final class StatechartCache {

    private final WorkItemWorkflowService workflows;
    private final String projectId;
    private final Map<String, Optional<Statechart>> cache = new HashMap<>();

    StatechartCache(WorkItemWorkflowService workflows, String projectId) {
        this.workflows = workflows;
        this.projectId = projectId;
    }

    /** The item's own statechart, or empty when it cannot be resolved. */
    Optional<Statechart> of(WorkItem item) {
        return of(item.getWorkflow(), item.getWorkflowVersion());
    }

    /** The statechart of a Workflow known only by slug and pinned version, or empty when it cannot be resolved. */
    Optional<Statechart> of(String workflow, Integer version) {
        return cache.computeIfAbsent(key(workflow, version),
                k -> resolve(() -> workflows.resolveFor(projectId, workflow, version)));
    }

    /** Whether the item's status is terminal in its own Workflow; false when that cannot be resolved. */
    boolean isTerminal(WorkItem item) {
        return of(item).map(c -> c.isTerminal(item.getCurrentStatus())).orElse(false);
    }

    /** Whether {@code status} is terminal in the named Workflow; false when that cannot be resolved. */
    boolean isTerminal(String workflow, Integer version, String status) {
        return of(workflow, version).map(c -> c.isTerminal(status)).orElse(false);
    }

    /** The item's current status in its own Workflow, if both resolve. */
    Optional<StatechartStatus> statusOf(WorkItem item) {
        return of(item).flatMap(c -> c.status(item.getCurrentStatus()));
    }

    private static String key(String workflow, Integer version) {
        return workflow + "@" + version;
    }

    private static Optional<Statechart> resolve(java.util.function.Supplier<Statechart> resolver) {
        try {
            return Optional.ofNullable(resolver.get());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
