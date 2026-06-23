package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An allowed move between two statuses (COND-18). Immutable value object parsed from a
 * {@code transitions[]} entry.
 *
 * @param from           source status id
 * @param to             target status id
 * @param label          human-readable action label shown to the doer
 * @param requiresReview whether the engine blocks this transition until a Review is recorded
 * @param reviewOutcomes allowed review verdicts (present when {@code requiresReview})
 * @param reviewerRole   project role that approves the gate ({@code ADMIN}|{@code CREATOR}|{@code REVIEWER}), or null
 * @param steps          automated actions on this transition (≤3)
 */
public record StatechartTransition(String from,
                                   String to,
                                   String label,
                                   boolean requiresReview,
                                   List<String> reviewOutcomes,
                                   String reviewerRole,
                                   List<StatechartStep> steps) {

    public StatechartTransition {
        reviewOutcomes = reviewOutcomes == null ? List.of() : List.copyOf(reviewOutcomes);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    static StatechartTransition parse(JsonNode node) {
        List<StatechartStep> steps = new java.util.ArrayList<>();
        JsonNode stepsNode = node.get("steps");
        if (stepsNode != null && stepsNode.isArray()) {
            stepsNode.forEach(s -> steps.add(StatechartStep.parse(s)));
        }
        return new StatechartTransition(
                Json.text(node, "from"),
                Json.text(node, "to"),
                Json.text(node, "label"),
                Json.bool(node, "requiresReview"),
                Json.stringList(node, "reviewOutcomes"),
                Json.text(node, "reviewerRole"),
                steps);
    }
}
