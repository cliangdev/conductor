package com.conductor.workflow.lifecycle;

import com.conductor.config.CacheConfig;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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

    private final Cache pinned;
    private final Cache latest;

    /** Wraps an absent result so "no such workflow" is cached too (Cache.get cannot store null cleanly). */
    private record Cached(Statechart statechart) {
    }

    /** Convenience for unit tests: private, uncached-across-instances caches. */
    public WorkflowDefinitionResolver(WorkflowDefinitionVersionRepository versionRepository) {
        this(versionRepository, new CacheConfig().cacheManager());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowDefinitionResolver(WorkflowDefinitionVersionRepository versionRepository,
                                      CacheManager cacheManager) {
        this.versionRepository = versionRepository;
        this.pinned = cacheManager.getCache(CacheConfig.STATECHART_PINNED);
        this.latest = cacheManager.getCache(CacheConfig.STATECHART_LATEST);
    }

    /**
     * Drop every cached "latest" resolution (and pinned ones, which only matter if a snapshot was removed).
     * Called when a Workflow is published, enabled, disabled, updated or deleted.
     */
    public void evictAll() {
        latest.clear();
        pinned.clear();
    }

    /**
     * Resolve a slug to its Statechart for a project: the latest PUBLISHED snapshot with that slug, else
     * empty. Used when binding a brand-new Work Item.
     */
    public Optional<Statechart> resolve(String projectId, String slug) {
        String key = projectId + "|" + slug;
        Cached cached = latest.get(key, Cached.class);
        if (cached == null) {
            cached = new Cached(versionRepository.findLatestPublished(projectId, slug)
                    .map(v -> Statechart.parse(v.getDefinition())).orElse(null));
            latest.put(key, cached);
        }
        return Optional.ofNullable(cached.statechart());
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
        // A pinned snapshot is immutable, so a hit never goes stale; only found snapshots are cached.
        String key = projectId + "|" + slug + "|" + version;
        Statechart hit = pinned.get(key, Statechart.class);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<WorkflowDefinitionVersion> snapshot =
                versionRepository.findByProjectSlugAndVersion(projectId, slug, version);
        if (snapshot.isPresent()) {
            Statechart parsed = Statechart.parse(snapshot.get().getDefinition());
            pinned.put(key, parsed);
            return Optional.of(parsed);
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
        // The snapshot's version column equals the statechart's own `version` field (publish keeps them in step).
        Optional<Statechart> chart = resolve(projectId, slug);
        if (chart.isPresent() && chart.get().version() != null) {
            return Optional.of(chart.get().version());
        }
        return versionRepository.findLatestPublished(projectId, slug)
                .map(WorkflowDefinitionVersion::getVersion);
    }
}
