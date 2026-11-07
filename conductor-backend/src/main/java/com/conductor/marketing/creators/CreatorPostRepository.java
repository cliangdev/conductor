package com.conductor.marketing.creators;

import com.conductor.entity.WorkItem;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Finds the Posts of one creator. A Post is a Work Item of type {@code POST} (every built-in publishing
 * Workflow) or any Work Item that has a publish destination; a TASK child of a creator is not one. A creator's
 * Posts are its children and the Posts that carry the tag {@code creator:<handle>}.
 */
interface CreatorPostRepository extends Repository<WorkItem, String> {

    /** What a creator's results list needs of a Post, without loading the entity or its tags. */
    record PostRow(String id, Integer sequenceNumber, String title, String status) {}

    @Query("""
            SELECT new com.conductor.marketing.creators.CreatorPostRepository$PostRow(
                       i.id, i.sequenceNumber, i.title, i.currentStatus)
              FROM WorkItem i
             WHERE i.project.id = :projectId AND i.parentWorkItemId = :creatorId
               AND (i.type = 'POST' OR EXISTS (SELECT 1 FROM PostPublishTarget t WHERE t.workItem.id = i.id))
            """)
    List<PostRow> findPostChildren(@Param("projectId") String projectId, @Param("creatorId") String creatorId);

    @Query("""
            SELECT new com.conductor.marketing.creators.CreatorPostRepository$PostRow(
                       i.id, i.sequenceNumber, i.title, i.currentStatus)
              FROM WorkItem i
             WHERE i.project.id = :projectId AND i.id <> :creatorId AND :tag MEMBER OF i.tags
               AND (i.type = 'POST' OR EXISTS (SELECT 1 FROM PostPublishTarget t WHERE t.workItem.id = i.id))
            """)
    List<PostRow> findPostsTagged(@Param("projectId") String projectId, @Param("creatorId") String creatorId,
                                  @Param("tag") String tag);
}
