package com.conductor.workflow.lifecycle;

import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
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

    private final WorkflowDefinitionVersionRepository versionRepository;
    private final Map<String, Statechart> builtIns;

    public WorkflowDefinitionResolver(WorkflowDefinitionVersionRepository versionRepository,
                                      ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
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
     * Resolve a slug to its Statechart for a project: the latest PUBLISHED snapshot with that slug, else the
     * built-in classpath definition, else empty. Used when binding a brand-new Work Item.
     */
    public Optional<Statechart> resolve(String projectId, String slug) {
        Optional<WorkflowDefinitionVersion> latest = versionRepository.findLatestPublished(projectId, slug);
        if (latest.isPresent()) {
            return Optional.of(Statechart.parse(latest.get().getDefinition()));
        }
        return Optional.ofNullable(builtIns.get(slug));
    }

    /**
     * Resolve a Work Item's <em>pinned</em> Workflow: the exact published {@code (slug, version)} snapshot, so
     * re-publishing the Workflow never changes the rules under an in-flight Work Item. Falls back to the
     * built-in classpath definition (built-ins have no DB snapshots), then to the latest published snapshot
     * for pre-pinning data. A null version means "latest" ({@link #resolve(String, String)}).
     */
    public Optional<Statechart> resolve(String projectId, String slug, Integer version) {
        if (version == null) {
            return resolve(projectId, slug);
        }
        Optional<WorkflowDefinitionVersion> snapshot =
                versionRepository.findByProjectSlugAndVersion(projectId, slug, version);
        if (snapshot.isPresent()) {
            return Optional.of(Statechart.parse(snapshot.get().getDefinition()));
        }
        if (builtIns.containsKey(slug)) {
            return Optional.of(builtIns.get(slug));
        }
        return resolve(projectId, slug);
    }

    /** Like {@link #resolve(String, String)} but throws {@link EntityNotFoundException} when unresolvable. */
    public Statechart resolveRequired(String projectId, String slug) {
        return resolve(projectId, slug)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found: " + slug));
    }

    /** Like {@link #resolve(String, String, Integer)} but throws when the Workflow cannot be resolved. */
    public Statechart resolveRequired(String projectId, String slug, Integer version) {
        return resolve(projectId, slug, version)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found: " + slug));
    }

    /**
     * The latest PUBLISHED snapshot version for a slug, taken from the version table's column (the value a
     * new Work Item pins to). Empty for a built-in workflow with no DB snapshots — the caller falls back to
     * the built-in's declared version.
     */
    public Optional<Integer> latestPublishedVersion(String projectId, String slug) {
        return versionRepository.findLatestPublished(projectId, slug)
                .map(WorkflowDefinitionVersion::getVersion);
    }

    /** Whether a slug names a built-in Workflow (resolvable for every project without a DB row). */
    public boolean isBuiltIn(String slug) {
        return builtIns.containsKey(slug);
    }
}
