package com.conductor.marketing.promises;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingMessageTagRepository extends JpaRepository<MarketingMessageTag, String> {

    List<MarketingMessageTag> findByProjectId(String projectId);

    Optional<MarketingMessageTag> findByProjectIdAndTargetKindAndTargetRef(String projectId, String targetKind,
                                                                           String targetRef);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    long deleteByProjectIdAndTargetKindAndTargetRef(String projectId, String targetKind, String targetRef);

    /**
     * Tags a target, or moves it to the new message (and replaces its label) when it is already tagged. One
     * statement, so two requests for the same target cannot both fail on the unique key.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO marketing_message_tag (id, project_id, message, target_kind, target_ref, label, created_by, created_at)
            VALUES (:id, :projectId, :message, :targetKind, :targetRef, CAST(:label AS VARCHAR),
                    CAST(:createdBy AS VARCHAR), now())
            ON CONFLICT (project_id, target_kind, target_ref)
            DO UPDATE SET message = EXCLUDED.message, label = EXCLUDED.label
            """, nativeQuery = true)
    int upsert(@Param("id") String id, @Param("projectId") String projectId, @Param("message") String message,
               @Param("targetKind") String targetKind, @Param("targetRef") String targetRef,
               @Param("label") String label, @Param("createdBy") String createdBy);
}
