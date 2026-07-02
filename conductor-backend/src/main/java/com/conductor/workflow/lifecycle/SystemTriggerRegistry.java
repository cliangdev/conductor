package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of system triggers a Workflow transition may declare on its {@code trigger} field (#240 §3). A system
 * trigger names an internal event that auto-advances a Work Item through a Workflow-declared edge — the seam
 * that lets a lifecycle react to more than the single hardcoded {@code pr_merged} event.
 *
 * <p>Mirrors {@link SkillRegistry}, but with only the built-in (classpath) layer: unlike a skill, a system
 * trigger needs backend dispatch code to mean anything (a service that fires {@code applySystemTransition} for
 * it), so the honest gate is "a shipped, data-declared trigger", not a DB free-for-all. Adding a trigger is a
 * data entry in {@code schema/examples/system-triggers.json} plus its dispatch wiring — never a schema enum edit.
 *
 * <p>{@link WorkflowDefinitionValidator} blocks Publish if a transition declares a trigger not registered here;
 * {@code WorkItemWorkflowService} consults {@link #bypassesReviewGate(String)} to decide whether a triggered
 * transition honors the Review gate ({@code pr_merged} bypasses it as the external authority; {@code
 * status_changed} honors it).
 */
@Component
public class SystemTriggerRegistry {

    private static final String REGISTRY_RESOURCE = "schema/examples/system-triggers.json";

    /** A shipped system trigger: its id, whether it bypasses the Review gate, and a human description. */
    public record SystemTrigger(String id, boolean bypassReviewGate, String description) {
    }

    private final List<SystemTrigger> triggers;
    private final Map<String, SystemTrigger> byId;

    public SystemTriggerRegistry(ObjectMapper objectMapper) {
        this.triggers = load(objectMapper);
        Map<String, SystemTrigger> index = new LinkedHashMap<>();
        for (SystemTrigger t : triggers) {
            index.put(t.id(), t);
        }
        this.byId = Map.copyOf(index);
    }

    private static List<SystemTrigger> load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(REGISTRY_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<SystemTrigger> parsed = new ArrayList<>();
            JsonNode arr = root.get("triggers");
            if (arr != null && arr.isArray()) {
                arr.forEach(t -> {
                    JsonNode id = t.get("id");
                    if (id != null && !id.isNull()) {
                        parsed.add(new SystemTrigger(
                                id.asText(),
                                t.hasNonNull("bypassReviewGate") && t.get("bypassReviewGate").asBoolean(),
                                t.hasNonNull("description") ? t.get("description").asText() : null));
                    }
                });
            }
            return List.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system trigger registry from " + REGISTRY_RESOURCE, e);
        }
    }

    /** True if {@code triggerId} is a registered system trigger. */
    public boolean isRegistered(String triggerId) {
        return byId.containsKey(triggerId);
    }

    /**
     * Whether a transition fired by {@code triggerId} bypasses the Review gate. Unknown triggers do not bypass
     * (the safe default); the validator rejects them at Publish anyway.
     */
    public boolean bypassesReviewGate(String triggerId) {
        SystemTrigger t = byId.get(triggerId);
        return t != null && t.bypassReviewGate();
    }

    /** All registered system triggers, in declaration order. */
    public List<SystemTrigger> all() {
        return triggers;
    }
}
