package com.conductor.creative;

import com.conductor.service.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reference-aware storage cleanup. Media is the one file library and Posts and Creative render frames
 * link to its objects instead of copying them, so one stored object can be named by several rows
 * ({@code creative_photo} gcs_path/poster_gcs_path, {@code creative_render_frame} gcs_path/poster_gcs_path,
 * {@code assets} gcs_path). Every path that deletes an object goes through here: it drops any candidate
 * path some row still names, then schedules the delete of the rest for after commit.
 *
 * <p>Call it <b>after</b> the caller's own deletes, inside the same transaction: pending changes are
 * flushed first, so the rows being deleted no longer count as references. A link created concurrently
 * between this check and the commit can still lose its object; that window is accepted.
 */
@Component
class StorageObjectReferences {

    private final StorageService storageService;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    StorageObjectReferences(StorageService storageService) {
        this.storageService = storageService;
    }

    /** Test seam: supply the EntityManager without Spring. */
    StorageObjectReferences(StorageService storageService, EntityManager entityManager) {
        this.storageService = storageService;
        this.entityManager = entityManager;
    }

    /** The subset of {@code gcsPaths} (deduplicated, order kept) that no row references. */
    List<String> unreferenced(Collection<String> gcsPaths) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String path : gcsPaths) {
            if (path != null && !path.isBlank()) {
                candidates.add(path);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        entityManager.flush();
        Set<String> referenced = new HashSet<>();
        collect(referenced, candidates, "SELECT p.gcsPath, p.posterGcsPath FROM CreativePhoto p "
                + "WHERE p.gcsPath IN :paths OR p.posterGcsPath IN :paths");
        collect(referenced, candidates, "SELECT f.gcsPath, f.posterGcsPath FROM CreativeRenderFrame f "
                + "WHERE f.gcsPath IN :paths OR f.posterGcsPath IN :paths");
        collect(referenced, candidates, "SELECT a.gcsPath FROM Asset a WHERE a.gcsPath IN :paths");
        return candidates.stream().filter(p -> !referenced.contains(p)).toList();
    }

    /** Schedules the best-effort delete, after commit, of every candidate path nothing references. */
    void deleteUnreferencedAfterCommit(Collection<String> gcsPaths, Logger log) {
        AfterCommitStorageCleanup.deleteAfterCommit(storageService, unreferenced(gcsPaths), log);
    }

    private void collect(Set<String> into, Set<String> candidates, String jpql) {
        for (Object row : entityManager.createQuery(jpql, Object.class).setParameter("paths", candidates).getResultList()) {
            if (row instanceof Object[] cols) {
                for (Object col : cols) {
                    if (col != null) {
                        into.add((String) col);
                    }
                }
            } else if (row != null) {
                into.add((String) row);
            }
        }
    }
}
