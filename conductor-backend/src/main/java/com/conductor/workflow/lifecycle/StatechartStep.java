package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An automated action attached to a transition (COND-18). Immutable value object.
 *
 * <p>For {@code kind == "skill"} the Step runs <em>locally</em> (Claude Code drives, reports via MCP);
 * the engine never executes it, and {@code BLOCKING} is an advisory convention. {@code http}/{@code notify}/
 * {@code set_field}/{@code create_sub_items} are engine-run (their executor dispatch is a later phase).
 *
 * @param kind        {@code skill} | {@code http} | {@code notify} | {@code set_field} | {@code create_sub_items}
 * @param mode        {@code BLOCKING} | {@code ASYNC}
 * @param skill       bindable skill id when {@code kind == "skill"} (else null)
 * @param typeVersion executor contract version (defaults to 1 when absent)
 * @param config      kind-specific configuration (opaque to the lifecycle domain)
 */
public record StatechartStep(String kind, String mode, String skill, Integer typeVersion, JsonNode config) {

    static StatechartStep parse(JsonNode node) {
        return new StatechartStep(
                Json.text(node, "kind"),
                Json.text(node, "mode"),
                Json.text(node, "skill"),
                Json.intOrNull(node, "type_version"),
                node.get("config"));
    }

    public boolean isSkill() {
        return "skill".equals(kind);
    }
}
