package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A stage a Work Item moves through (COND-18). Immutable value object parsed from a
 * {@code statuses[]} entry of a Workflow definition.
 *
 * @param id       UPPER_SNAKE status id (e.g. {@code DRAFT})
 * @param category lane grouping — {@code open} | {@code in_progress} | {@code terminal}
 * @param initial  whether this is the start status (exactly one per Statechart)
 * @param terminal whether this is an end status
 */
public record StatechartStatus(String id, String category, boolean initial, boolean terminal) {

    static StatechartStatus parse(JsonNode node) {
        return new StatechartStatus(
                Json.text(node, "id"),
                Json.text(node, "category"),
                Json.bool(node, "initial"),
                Json.bool(node, "terminal"));
    }
}
