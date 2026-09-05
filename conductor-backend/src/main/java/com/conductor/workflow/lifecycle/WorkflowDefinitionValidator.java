package com.conductor.workflow.lifecycle;

import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.WorkflowValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a Workflow {@code definition} (COND-18) — the sole write path the Builder and any future
 * authoring skill/MCP funnel through, so all producers inherit the same guardrails. Two layers, mirroring
 * how {@code WorkflowValidator} splits YAML structure from semantic checks:
 *
 * <ol>
 *   <li><b>Structural</b> — the merged JSON Schema {@code schema/workflow-definition-v1.schema.json}
 *       (field types, enums, required keys, the per-array hard caps), enforced at runtime via networknt so
 *       the schema is the single source of truth (no caps re-encoded in Java to drift).</li>
 *   <li><b>Semantic</b> — the {@code x-semantic-rules} that span the document (exactly one initial status,
 *       reachability, edge endpoints, per-status/review caps, skill-exists), enforced here in Java.</li>
 * </ol>
 *
 * Returns the existing {@link WorkflowValidationResult} so the controller's warning plumbing is reused.
 */
@Component
public class WorkflowDefinitionValidator {

    private static final String SCHEMA_RESOURCE = "schema/workflow-definition-v1.schema.json";
    private static final int MAX_TRANSITIONS_PER_STATUS = 5;
    private static final int MAX_REVIEW_GATED_TRANSITIONS = 3;
    /**
     * The system triggers that cascade — each hop fires the next edge declaring the same trigger — and so
     * could auto-loop on a cyclic chart; see the cycle check below. {@code status_changed} fires on the
     * event it also produces; {@code review_approved} continues along consecutive edges of its own trigger.
     */
    private static final List<String> CASCADING_TRIGGERS = List.of("status_changed", "review_approved");

    private final Schema schema;
    private final SkillRegistry skillRegistry;
    private final SystemTriggerRegistry systemTriggerRegistry;
    private final PublishPlatformRegistry publishPlatformRegistry;

    public WorkflowDefinitionValidator(SkillRegistry skillRegistry, SystemTriggerRegistry systemTriggerRegistry,
                                       PublishPlatformRegistry publishPlatformRegistry) {
        this.skillRegistry = skillRegistry;
        this.systemTriggerRegistry = systemTriggerRegistry;
        this.publishPlatformRegistry = publishPlatformRegistry;
        this.schema = loadSchema();
    }

    private static Schema loadSchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow definition schema from " + SCHEMA_RESOURCE, e);
        }
    }

    public WorkflowValidationResult validate(String projectId, JsonNode definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (definition == null || definition.isNull()) {
            errors.add("definition is required");
            return new WorkflowValidationResult(errors, warnings);
        }

        // 1) Structural — the JSON Schema. If it fails, the document isn't safe to parse semantically.
        // definition arrives as a Jackson 2 JsonNode; serialize to a string so the validator (Jackson 3
        // internally, per json-schema-validator 3.x) never has to touch our JsonNode type.
        List<Error> structural = schema.validate(definition.toString(), InputFormat.JSON);
        if (!structural.isEmpty()) {
            structural.stream()
                    .map(e -> "schema: " + e)
                    .sorted()
                    .forEach(errors::add);
            return new WorkflowValidationResult(errors, warnings);
        }

        // 2) Semantic — the x-semantic-rules, over the parsed Statechart. Skill-exists is project-scoped
        // (built-in registry + the project's registered skills).
        Statechart sc = Statechart.parse(definition);
        validateSemantics(projectId, sc, errors);

        return new WorkflowValidationResult(errors, warnings);
    }

    private void validateSemantics(String projectId, Statechart sc, List<String> errors) {
        Set<String> statusIds = new HashSet<>();
        for (StatechartStatus s : sc.statuses()) {
            statusIds.add(s.id());
        }

        // Exactly one initial status.
        long initialCount = sc.statuses().stream().filter(StatechartStatus::initial).count();
        if (initialCount != 1) {
            errors.add("exactly one status must be initial, found " + initialCount);
        }

        // At least one terminal status.
        boolean anyTerminal = sc.statuses().stream().anyMatch(StatechartStatus::terminal);
        if (!anyTerminal) {
            errors.add("at least one status must be terminal");
        }

        // Edge endpoints must reference existing statuses; per-status transition cap; declared system trigger
        // must be registered (an open-string schema shape gated here against the SystemTriggerRegistry, so a
        // new trigger is a data+dispatch change, not a schema enum edit).
        Map<String, Integer> outDegree = new HashMap<>();
        for (StatechartTransition t : sc.transitions()) {
            if (!statusIds.contains(t.from())) {
                errors.add("transition references unknown 'from' status: " + t.from());
            }
            if (!statusIds.contains(t.to())) {
                errors.add("transition references unknown 'to' status: " + t.to());
            }
            if (t.trigger() != null && !systemTriggerRegistry.isRegistered(t.trigger())) {
                errors.add("transition " + t.from() + " -> " + t.to() + " uses unknown system trigger '"
                        + t.trigger() + "'");
            }
            outDegree.merge(t.from(), 1, Integer::sum);
        }
        outDegree.forEach((from, count) -> {
            if (count > MAX_TRANSITIONS_PER_STATUS) {
                errors.add("status '" + from + "' has " + count + " transitions, exceeds the cap of "
                        + MAX_TRANSITIONS_PER_STATUS);
            }
        });

        // At most 3 review-gated transitions.
        long gated = sc.transitions().stream().filter(StatechartTransition::requiresReview).count();
        if (gated > MAX_REVIEW_GATED_TRANSITIONS) {
            errors.add(gated + " review-gated transitions, exceeds the cap of " + MAX_REVIEW_GATED_TRANSITIONS);
        }

        validatePublishesFrom(sc, statusIds, errors);

        // Every skill Step references a bindable skill id. Load the project's bindable set once (a single
        // query), lazily — statecharts with no skill steps issue no query at all.
        Set<String> bindableSkills = null;
        for (StatechartTransition t : sc.transitions()) {
            for (StatechartStep step : t.steps()) {
                if (!step.isSkill()) {
                    continue;
                }
                if (bindableSkills == null) {
                    bindableSkills = skillRegistry.bindableSkillIds(projectId);
                }
                if (!bindableSkills.contains(step.skill())) {
                    errors.add("step binds unknown skill '" + step.skill() + "' on transition "
                            + t.from() + " -> " + t.to());
                }
            }
        }

        // Reachability — every non-terminal status can reach some terminal status. Only meaningful once
        // endpoints are valid; skip if earlier endpoint errors already exist to avoid noise.
        if (errors.isEmpty()) {
            validateReachability(sc, errors);
            for (String trigger : CASCADING_TRIGGERS) {
                validateNoCascadeCycle(sc, trigger, errors);
            }
        }
    }

    /**
     * A Workflow that publishes — one whose {@code asset_types} names a platform — has to say which status
     * its Posts wait in for their fire time, because that is the status the pollers dispatch from and the
     * entry the publish validators guard. {@code publishes_from} says so explicitly; a chart that predates
     * the field is accepted when it has a status literally named {@code SCHEDULED}, which is what every
     * pinned MARKETING snapshot has. Either way the status must exist, must not be terminal (a Post waits
     * there, it does not end there) and must lead to a terminal status (publishing is how a Post finishes).
     */
    private void validatePublishesFrom(Statechart sc, Set<String> statusIds, List<String> errors) {
        Optional<String> declared = sc.publishesFrom();
        if (declared.isPresent() && !statusIds.contains(declared.get())) {
            errors.add("publishes_from references unknown status: " + declared.get());
            return;
        }
        Optional<String> scheduled = PublishingWorkflow.scheduledStatus(sc);
        if (scheduled.isEmpty()) {
            if (publishPlatformRegistry.declaresPublishing(sc)) {
                errors.add("a Workflow whose asset_types name a publishable platform must declare"
                        + " publishes_from: the status its items wait in for their fire time");
            }
            return;
        }
        String status = scheduled.get();
        if (sc.isTerminal(status)) {
            errors.add("publishes_from status '" + status + "' is terminal; an item must be able to wait there");
            return;
        }
        boolean reachesTerminal = sc.transitionsFrom(status).stream()
                .map(StatechartTransition::to)
                .anyMatch(sc::isTerminal);
        if (declared.isPresent() && !reachesTerminal) {
            errors.add("publishes_from status '" + status + "' has no transition to a terminal status,"
                    + " so a published item could never finish");
        }
    }

    /**
     * A cycle formed purely by edges of one cascading trigger would auto-loop at runtime: each hop fires the
     * next edge declaring the same trigger. The dispatcher caps it, but the workflow is still broken
     * authoring, so reject it at publish. Only cascading triggers are checked — a {@code pr_merged} "cycle"
     * needs a fresh external merge per hop, so it is left alone.
     */
    private void validateNoCascadeCycle(Statechart sc, String trigger, List<String> errors) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (StatechartTransition t : sc.transitions()) {
            if (trigger.equals(t.trigger())) {
                adjacency.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String start : adjacency.keySet()) {
            if (hasStatusChangedCycle(start, adjacency, visiting, done)) {
                errors.add(trigger + " transitions form a cycle through '" + start
                        + "', which would auto-loop at runtime");
                return;
            }
        }
    }

    private boolean hasStatusChangedCycle(String node, Map<String, List<String>> adjacency,
                                          Set<String> visiting, Set<String> done) {
        visiting.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (visiting.contains(next)) {
                return true;
            }
            if (!done.contains(next) && hasStatusChangedCycle(next, adjacency, visiting, done)) {
                return true;
            }
        }
        visiting.remove(node);
        done.add(node);
        return false;
    }

    private void validateReachability(Statechart sc, List<String> errors) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (StatechartTransition t : sc.transitions()) {
            adjacency.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
        }
        for (StatechartStatus s : sc.statuses()) {
            if (s.terminal()) {
                continue;
            }
            if (!reachesTerminal(s.id(), sc, adjacency)) {
                errors.add("status '" + s.id() + "' cannot reach a terminal status (dead-end)");
            }
        }
    }

    private boolean reachesTerminal(String start, Statechart sc, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (sc.isTerminal(current)) {
                return true;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }
}
