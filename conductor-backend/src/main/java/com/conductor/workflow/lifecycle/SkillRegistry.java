package com.conductor.workflow.lifecycle;

import com.conductor.repository.ProjectSkillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of bindable Claude Code skill ids a Workflow definition may reference from a {@code skill} Step
 * (COND-18 {@code AC-P0-2.5}). Two layers, mirroring built-in vs. DB-authored Workflows:
 *
 * <ol>
 *   <li><b>Built-in</b> — the classpath contract {@code schema/examples/skill-registry.json}, loaded once
 *       (the shipped {@code conductor:*} skills, available to every project).</li>
 *   <li><b>Project-scoped</b> — {@code project_skills} rows a user/authoring agent registers at runtime, so a
 *       non-engineering Workflow can bind its own skill and Publish without a backend redeploy.</li>
 * </ol>
 *
 * The {@link WorkflowDefinitionValidator} blocks Publish if a step binds a skill in neither layer.
 */
@Component
public class SkillRegistry {

    private static final String REGISTRY_RESOURCE = "schema/examples/skill-registry.json";

    /** A shipped, project-agnostic bindable skill. */
    public record BuiltInSkill(String id, String label, String description) {
    }

    private final List<BuiltInSkill> builtInSkills;
    private final Set<String> builtInSkillIds;
    private final ProjectSkillRepository projectSkillRepository;

    public SkillRegistry(ObjectMapper objectMapper, ProjectSkillRepository projectSkillRepository) {
        this.builtInSkills = load(objectMapper);
        this.builtInSkillIds = Set.copyOf(builtInSkills.stream().map(BuiltInSkill::id).toList());
        this.projectSkillRepository = projectSkillRepository;
    }

    private static List<BuiltInSkill> load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(REGISTRY_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<BuiltInSkill> skills = new ArrayList<>();
            JsonNode arr = root.get("skills");
            if (arr != null && arr.isArray()) {
                arr.forEach(s -> {
                    JsonNode id = s.get("id");
                    if (id != null && !id.isNull()) {
                        skills.add(new BuiltInSkill(
                                id.asText(),
                                s.hasNonNull("label") ? s.get("label").asText() : null,
                                s.hasNonNull("description") ? s.get("description").asText() : null));
                    }
                });
            }
            return List.copyOf(skills);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load skill registry from " + REGISTRY_RESOURCE, e);
        }
    }

    /**
     * All skill ids bindable in {@code projectId} — the shipped built-ins plus the project's registered skills.
     * Resolved in a single {@code findAllByProjectId} query; a validator loop over skill steps should fetch this
     * once and check membership in memory rather than probing per step.
     */
    public Set<String> bindableSkillIds(String projectId) {
        Set<String> ids = new HashSet<>(builtInSkillIds);
        projectSkillRepository.findAllByProjectId(projectId).forEach(ps -> ids.add(ps.getSkillId()));
        return ids;
    }

    /** True if {@code skillId} is a shipped built-in (project-agnostic). */
    public boolean isBuiltIn(String skillId) {
        return builtInSkillIds.contains(skillId);
    }

    /** Immutable set of shipped built-in skill ids. */
    public Set<String> builtInSkillIds() {
        return builtInSkillIds;
    }

    public List<BuiltInSkill> builtInSkills() {
        return builtInSkills;
    }
}
