package com.conductor.workflow.lifecycle;

import com.conductor.workflow.WorkflowValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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

    private final JsonSchema schema;
    private final SkillRegistry skillRegistry;

    public WorkflowDefinitionValidator(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.schema = loadSchema();
    }

    private static JsonSchema loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        ClassPathResource resource = new ClassPathResource(SCHEMA_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return factory.getSchema(in);
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
        Set<ValidationMessage> structural = schema.validate(definition);
        if (!structural.isEmpty()) {
            structural.stream()
                    .map(m -> "schema: " + m.getMessage())
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

        // Edge endpoints must reference existing statuses; per-status transition cap.
        Map<String, Integer> outDegree = new HashMap<>();
        for (StatechartTransition t : sc.transitions()) {
            if (!statusIds.contains(t.from())) {
                errors.add("transition references unknown 'from' status: " + t.from());
            }
            if (!statusIds.contains(t.to())) {
                errors.add("transition references unknown 'to' status: " + t.to());
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
        }
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
