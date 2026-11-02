package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.workflow.lifecycle.Statechart;

import java.time.OffsetDateTime;

/**
 * Stamps {@code started_at} / {@code completed_at} on a Work Item as its status changes, so cycle time and
 * throughput can be read later without replaying history. Done on write, wherever a status changes, rather
 * than by anything periodic.
 *
 * <ul>
 *   <li>{@code started_at}: the first move into an {@code in_progress}-category status. Never reset, so a
 *       Stop and a re-Start keep the original.
 *   <li>{@code completed_at}: entering a terminal status. Cleared when the item leaves a terminal status
 *       again, so a reopened item is not counted as finished.
 * </ul>
 *
 * <p>"In progress" and "terminal" are the item's own Workflow's words, read from the statechart.
 */
final class CycleTimestamps {

    static final String IN_PROGRESS_CATEGORY = "in_progress";

    private CycleTimestamps() {
    }

    /** Applies the stamps for a move {@code fromStatus -> toStatus}; a no-op when the status did not change. */
    static void stamp(Statechart statechart, WorkItem item, String fromStatus, String toStatus, OffsetDateTime now) {
        if (statechart == null || toStatus == null || toStatus.equals(fromStatus)) {
            return;
        }
        boolean toTerminal = statechart.isTerminal(toStatus);
        if (toTerminal) {
            if (item.getCompletedAt() == null) {
                item.setCompletedAt(now);
            }
        } else if (fromStatus != null && statechart.isTerminal(fromStatus)) {
            item.setCompletedAt(null);
        }
        if (item.getStartedAt() == null
                && statechart.status(toStatus).map(s -> IN_PROGRESS_CATEGORY.equals(s.category())).orElse(false)) {
            item.setStartedAt(now);
        }
    }
}
