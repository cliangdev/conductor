package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A typed field a Workflow declares on its Work Items (values live in {@code work_items.fields}).
 * Immutable value object.
 *
 * @param key        stable camelCase key the value is stored and sent under
 * @param label      human display name
 * @param type       {@code text} | {@code longtext} | {@code date} | {@code number} | {@code enum} | {@code url}
 * @param options    allowed values; only meaningful for {@code enum}
 * @param requiredIn status ids a Work Item cannot move into while this field is empty
 */
public record StatechartField(String key, String label, String type, List<String> options,
                              List<String> requiredIn) {

    static StatechartField parse(JsonNode node) {
        return new StatechartField(
                Json.text(node, "key"),
                Json.text(node, "label"),
                Json.text(node, "type"),
                Json.stringList(node, "options"),
                Json.stringList(node, "requiredIn"));
    }
}
