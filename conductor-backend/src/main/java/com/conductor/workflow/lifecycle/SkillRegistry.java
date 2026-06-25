package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The set of bindable Claude Code skill ids a Workflow definition may reference from a {@code skill} Step
 * (COND-18 {@code AC-P0-2.5}). Loaded once from the classpath contract
 * {@code schema/examples/skill-registry.json}. The {@link WorkflowDefinitionValidator} blocks Publish if a
 * step binds a skill not in this set.
 *
 * <p>Built-in registry for now; a project-scoped registry layers on later the same way built-in vs.
 * DB-authored Workflows do.
 */
@Component
public class SkillRegistry {

    private static final String REGISTRY_RESOURCE = "schema/examples/skill-registry.json";

    private final Set<String> skillIds;

    public SkillRegistry(ObjectMapper objectMapper) {
        this.skillIds = load(objectMapper);
    }

    private static Set<String> load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(REGISTRY_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Set<String> ids = new LinkedHashSet<>();
            JsonNode skills = root.get("skills");
            if (skills != null && skills.isArray()) {
                skills.forEach(s -> {
                    JsonNode id = s.get("id");
                    if (id != null && !id.isNull()) {
                        ids.add(id.asText());
                    }
                });
            }
            return Set.copyOf(ids);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load skill registry from " + REGISTRY_RESOURCE, e);
        }
    }

    public boolean isRegistered(String skillId) {
        return skillIds.contains(skillId);
    }

    public Set<String> skillIds() {
        return skillIds;
    }
}
