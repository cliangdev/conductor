package com.conductor.service.view;

import java.util.List;

/**
 * The doer-projection of a Work Item's available next statuses, derived from its bound Workflow statechart.
 * Domain view returned by the workflow service; controllers map it to their API DTO.
 */
public record AvailableTransitionsView(
        String workflow,
        String currentStatus,
        String noun,
        List<Transition> transitions) {

    /** A single available transition edge. */
    public record Transition(String toStatus, String label, boolean requiresReview) {
    }
}
