package com.conductor.workflow.lifecycle;

import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves a Workflow slug (e.g. {@code ENGINEERING}) to its {@link Statechart} (COND-18). Resolution is
 * DB-only: a project resolves the PUBLISHED snapshot with that slug, or a specific pinned {@code (slug,
 * version)} snapshot for an in-flight Work Item. Every project is seeded its lifecycle workflows as real
 * DB rows ({@code WorkflowSeeder} / Flyway), so there is no classpath built-in fallback — an unresolvable
 * slug is simply not found.
 */
@Component
public class WorkflowDefinitionResolver {

    private final WorkflowDefinitionVersionRepository versionRepository;

    public WorkflowDefinitionResolver(WorkflowDefinitionVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    /**
     * Resolve a slug to its Statechart for a project: the latest PUBLISHED snapshot with that slug, else
     * empty. Used when binding a brand-new Work Item.
     */
    public Optional<Statechart> resolve(String projectId, String slug) {
        return versionRepository.findLatestPublished(projectId, slug)
                .map(v -> Statechart.parse(v.getDefinition()));
    }

    /**
     * Resolve a Work Item's <em>pinned</em> Workflow: the exact published {@code (slug, version)} snapshot, so
     * re-publishing the Workflow never changes the rules under an in-flight Work Item. A null version means
     * "latest" ({@link #resolve(String, String)}).
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
     * new Work Item pins to). Empty when the project has no published snapshot for the slug.
     */
    public Optional<Integer> latestPublishedVersion(String projectId, String slug) {
        return versionRepository.findLatestPublished(projectId, slug)
                .map(WorkflowDefinitionVersion::getVersion);
    }
}
