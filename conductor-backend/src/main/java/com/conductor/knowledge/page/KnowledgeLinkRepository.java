package com.conductor.knowledge.page;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface KnowledgeLinkRepository extends JpaRepository<KnowledgeLink, String> {

    void deleteByFromPageId(String fromPageId);

    /**
     * Fixes up links that were pointing at {@code toPath} before it existed (dangling,
     * {@code resolvedPageId IS NULL}) once a page is created/resurrected at that path.
     * {@code flushAutomatically}: bulk JPQL updates don't participate in Hibernate's normal
     * flush-before-query ordering, so without it this can race the page insert it's referencing
     * (the just-created page's row not existing yet -> FK violation on {@code resolved_page_id}).
     * {@code clearAutomatically}: without it, a {@link KnowledgeLink} already loaded earlier in the
     * same transaction keeps serving its stale pre-update field instead of the new value.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE KnowledgeLink l SET l.resolvedPageId = :pageId "
            + "WHERE l.projectId = :projectId AND l.toPath = :toPath AND l.resolvedPageId IS NULL")
    int resolveDangling(@Param("projectId") String projectId, @Param("toPath") String toPath, @Param("pageId") String pageId);

    /**
     * Un-resolves incoming links when their target page is (soft-)deleted. {@code clearAutomatically}
     * because a bulk update bypasses the persistence context -- without it, a {@link KnowledgeLink}
     * already loaded earlier in the same transaction would keep serving its stale pre-update field.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE KnowledgeLink l SET l.resolvedPageId = NULL WHERE l.resolvedPageId = :pageId")
    int unresolveLinksTo(@Param("pageId") String pageId);
}
