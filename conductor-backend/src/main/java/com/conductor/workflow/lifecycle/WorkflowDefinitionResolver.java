package com.conductor.workflow.lifecycle;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a Workflow slug (e.g. {@code ENGINEERING}) to its {@link Statechart} (COND-18). DB-first: a
 * project that has authored/cloned a definition gets that PUBLISHED row; otherwise the resolver falls back
 * to a <b>built-in classpath definition</b>. This is the load-bearing future-proofing seam — adding or
 * shipping a Workflow is a data/classpath change, never a migration to issues/assets/reviews.
 *
 * <p>Built-ins (e.g. the Engineering preset that reproduces today's hardcoded loop) are read-only JSON on
 * the classpath; user-authored Workflows are PUBLISHED rows. Same model the connector framework uses for
 * built-in vs. configured.
 */
@Component
public class WorkflowDefinitionResolver {

    /** Built-in Workflow slug -> classpath definition resource. */
    private static final Map<String, String> BUILT_IN_RESOURCES = Map.of(
            "ENGINEERING", "schema/examples/engineering.workflow.json");

    private final WorkflowDefinitionRepository definitionRepository;
    private final Map<String, Statechart> builtIns;

    public WorkflowDefinitionResolver(WorkflowDefinitionRepository definitionRepository,
                                      ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.builtIns = loadBuiltIns(objectMapper);
    }

    private static Map<String, Statechart> loadBuiltIns(ObjectMapper objectMapper) {
        Map<String, Statechart> loaded = new LinkedHashMap<>();
        BUILT_IN_RESOURCES.forEach((slug, resourcePath) -> {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream in = resource.getInputStream()) {
                JsonNode def = objectMapper.readTree(in);
                loaded.put(slug, Statechart.parse(def));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load built-in workflow '" + slug + "' from "
                        + resourcePath, e);
            }
        });
        return Map.copyOf(loaded);
    }

    /**
     * Resolve a slug to its Statechart for a project: the latest PUBLISHED project definition with that slug,
     * else the built-in classpath definition, else empty.
     */
    public Optional<Statechart> resolve(String projectId, String slug) {
        Optional<WorkflowDefinition> dbRow = definitionRepository.findLatestPublishedBySlug(projectId, slug);
        if (dbRow.isPresent() && dbRow.get().getDefinition() != null) {
            return Optional.of(Statechart.parse(dbRow.get().getDefinition()));
        }
        return Optional.ofNullable(builtIns.get(slug));
    }

    /** Like {@link #resolve} but throws {@link EntityNotFoundException} when the Workflow cannot be resolved. */
    public Statechart resolveRequired(String projectId, String slug) {
        return resolve(projectId, slug)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found: " + slug));
    }

    /** Whether a slug names a built-in Workflow (resolvable for every project without a DB row). */
    public boolean isBuiltIn(String slug) {
        return builtIns.containsKey(slug);
    }
}
