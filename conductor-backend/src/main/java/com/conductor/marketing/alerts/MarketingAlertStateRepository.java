package com.conductor.marketing.alerts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Besides plain reads, the state row is changed only by the compare-and-set statements below. Cloud Run runs several
 * instances, so two evaluations of one workspace can read the same state and both decide to post; the row count of
 * these statements says which of them actually moved the state (1) and which lost (0). Only the winner posts.
 * They rely on {@code uq_marketing_alert_state (project_id, alert_key)} (V192) and need a transaction around them,
 * which the evaluator supplies.
 */
public interface MarketingAlertStateRepository extends JpaRepository<MarketingAlertState, String> {

    List<MarketingAlertState> findByProjectId(String projectId);

    /** Records a key seen for the first time; 0 when another evaluation recorded it first. */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO marketing_alert_state (id, project_id, alert_key, state, fingerprint, details, first_seen_at)
            VALUES (:id, :projectId, :alertKey, 'ACTIVE', :fingerprint, CAST(:details AS jsonb), :firstSeenAt)
            ON CONFLICT (project_id, alert_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("id") String id, @Param("projectId") String projectId,
                       @Param("alertKey") String alertKey, @Param("fingerprint") String fingerprint,
                       @Param("details") String detailsJson, @Param("firstSeenAt") OffsetDateTime firstSeenAt);

    /**
     * Moves a row that is still exactly as it was read (same state and fingerprint) to ACTIVE with new figures,
     * either an update to a standing alert or one coming back after RESOLVED; 0 when it has moved since.
     * {@code firstSeenAt} restarts the alert's life when it comes back, and is null for an update.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE marketing_alert_state
               SET state = 'ACTIVE', fingerprint = :fingerprint, details = CAST(:details AS jsonb),
                   first_seen_at = COALESCE(CAST(:firstSeenAt AS timestamptz), first_seen_at),
                   resolved_at = NULL
             WHERE project_id = :projectId AND alert_key = :alertKey
               AND state = :expectedState AND fingerprint = :expectedFingerprint
            """)
    int reactivateIfUnchanged(@Param("projectId") String projectId, @Param("alertKey") String alertKey,
                              @Param("expectedState") String expectedState,
                              @Param("expectedFingerprint") String expectedFingerprint,
                              @Param("fingerprint") String fingerprint, @Param("details") String detailsJson,
                              @Param("firstSeenAt") OffsetDateTime firstSeenAt);

    /** Marks a row that is still ACTIVE with the fingerprint that was read as RESOLVED; 0 when it has moved. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE marketing_alert_state
               SET state = 'RESOLVED', resolved_at = :resolvedAt
             WHERE project_id = :projectId AND alert_key = :alertKey
               AND state = 'ACTIVE' AND fingerprint = :expectedFingerprint
            """)
    int resolveIfUnchanged(@Param("projectId") String projectId, @Param("alertKey") String alertKey,
                           @Param("expectedFingerprint") String expectedFingerprint,
                           @Param("resolvedAt") OffsetDateTime resolvedAt);

    /** Stamps when the message that the winner of a state change sent went out. */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE marketing_alert_state SET last_posted_at = :at"
            + " WHERE project_id = :projectId AND alert_key = :alertKey")
    int markPosted(@Param("projectId") String projectId, @Param("alertKey") String alertKey,
                   @Param("at") OffsetDateTime at);
}
