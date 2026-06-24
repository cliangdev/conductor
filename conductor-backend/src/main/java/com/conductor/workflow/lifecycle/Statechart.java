package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable, parsed form of a Workflow {@code definition} (COND-18) — the rich domain model of the
 * Lifecycle bounded context. It encapsulates the statechart and answers the questions the
 * {@link WorkflowEngine} and the doer projection need: which status is initial/terminal, which transitions
 * leave a status, and whether a given move is allowed and gated.
 *
 * <p>This is a value object, not a JPA entity: the {@code WorkflowDefinition} entity stays anemic and stores
 * the raw JSON; behavior lives here. Build one via {@link #parse(JsonNode)}.
 */
public final class Statechart {

    private final String slug;
    private final String area;
    private final Integer version;
    private final String state;
    private final Integer schemaVersion;
    private final String noun;
    private final String defaultView;
    private final List<String> types;
    private final List<String> assetTypes;
    private final List<StatechartStatus> statuses;
    private final List<StatechartTransition> transitions;
    private final StatechartMetric metric;
    private final Map<String, StatechartStatus> statusById;

    private Statechart(String slug, String area, Integer version, String state, Integer schemaVersion,
                       String noun, String defaultView, List<String> types, List<String> assetTypes,
                       List<StatechartStatus> statuses, List<StatechartTransition> transitions,
                       StatechartMetric metric) {
        this.slug = slug;
        this.area = area;
        this.version = version;
        this.state = state;
        this.schemaVersion = schemaVersion;
        this.noun = noun;
        this.defaultView = defaultView;
        this.types = List.copyOf(types);
        this.assetTypes = List.copyOf(assetTypes);
        this.statuses = List.copyOf(statuses);
        this.transitions = List.copyOf(transitions);
        this.metric = metric;
        Map<String, StatechartStatus> byId = new LinkedHashMap<>();
        for (StatechartStatus s : statuses) {
            byId.put(s.id(), s);
        }
        this.statusById = Map.copyOf(byId);
    }

    /** Parse a Workflow definition JSON document into an immutable Statechart. Assumes structurally valid input
     *  (the {@code WorkflowDefinitionValidator} is the gate); missing optional fields degrade gracefully. */
    public static Statechart parse(JsonNode def) {
        List<StatechartStatus> statuses = new java.util.ArrayList<>();
        JsonNode statusesNode = def.get("statuses");
        if (statusesNode != null && statusesNode.isArray()) {
            statusesNode.forEach(n -> statuses.add(StatechartStatus.parse(n)));
        }
        List<StatechartTransition> transitions = new java.util.ArrayList<>();
        JsonNode transitionsNode = def.get("transitions");
        if (transitionsNode != null && transitionsNode.isArray()) {
            transitionsNode.forEach(n -> transitions.add(StatechartTransition.parse(n)));
        }
        return new Statechart(
                Json.text(def, "id"),
                Json.text(def, "area"),
                Json.intOrNull(def, "version"),
                Json.text(def, "state"),
                Json.intOrNull(def, "schemaVersion"),
                Json.text(def, "noun"),
                Json.text(def, "default_view"),
                Json.stringList(def, "types"),
                Json.stringList(def, "asset_types"),
                statuses,
                transitions,
                StatechartMetric.parse(def.get("metric")));
    }

    public String slug() { return slug; }
    public String area() { return area; }
    public Integer version() { return version; }
    public String state() { return state; }
    public Integer schemaVersion() { return schemaVersion; }
    /** Display noun for this Workflow's Work Items (e.g. {@code Issue}); falls back to "Work Item". */
    public String noun() { return noun == null ? "Work Item" : noun; }
    public String defaultView() { return defaultView; }
    public List<String> types() { return types; }
    public List<String> assetTypes() { return assetTypes; }
    public List<StatechartStatus> statuses() { return statuses; }
    public List<StatechartTransition> transitions() { return transitions; }
    /** The optional Outcome Metric this Workflow declares, or null if it opts out. */
    public StatechartMetric metric() { return metric; }

    public Optional<StatechartStatus> status(String id) {
        return Optional.ofNullable(statusById.get(id));
    }

    public boolean hasStatus(String id) {
        return statusById.containsKey(id);
    }

    public boolean isTerminal(String statusId) {
        StatechartStatus s = statusById.get(statusId);
        return s != null && s.terminal();
    }

    /** The single initial status, or empty if none is declared. */
    public Optional<StatechartStatus> initialStatus() {
        return statuses.stream().filter(StatechartStatus::initial).findFirst();
    }

    /** All transitions whose {@code from} equals the given status, in declaration order. */
    public List<StatechartTransition> transitionsFrom(String fromStatus) {
        return transitions.stream().filter(t -> t.from().equals(fromStatus)).toList();
    }

    /** The transition matching {@code from -> to}, if the edge exists. */
    public Optional<StatechartTransition> transition(String fromStatus, String toStatus) {
        return transitions.stream()
                .filter(t -> t.from().equals(fromStatus) && t.to().equals(toStatus))
                .findFirst();
    }
}
