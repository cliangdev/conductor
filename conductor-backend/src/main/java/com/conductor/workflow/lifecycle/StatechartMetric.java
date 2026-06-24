package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The optional Outcome Metric a Workflow declares (COND-18). Immutable value object.
 *
 * @param name      metric name (e.g. retention)
 * @param unit      optional unit
 * @param direction {@code higher_better} | {@code lower_better} — drives top-performer/regression ranking
 */
public record StatechartMetric(String name, String unit, String direction) {

    static StatechartMetric parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new StatechartMetric(
                Json.text(node, "name"),
                Json.text(node, "unit"),
                Json.text(node, "direction"));
    }
}
