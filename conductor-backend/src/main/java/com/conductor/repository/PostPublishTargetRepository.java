package com.conductor.repository;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface PostPublishTargetRepository extends JpaRepository<PostPublishTarget, String> {

    /** Every destination that publishes (or published) through one connection, in any state. */
    List<PostPublishTarget> findAllByConnectionId(String connectionId);

    List<PostPublishTarget> findAllByWorkItemId(String workItemId);

    List<PostPublishTarget> findAllByWorkItemIdAndState(String workItemId, PostPublishTargetState state);

    /**
     * Published destinations on one connection that are still worth reading performance numbers for:
     * they have a platform post id to ask about and fired within the window the feed's quota allows.
     * Newest first, so a project with a long history reads its recent posts before the budget runs out.
     */
    @Query("""
            SELECT t FROM PostPublishTarget t
             WHERE t.connectionId = :connectionId
               AND t.state = com.conductor.entity.PostPublishTargetState.PUBLISHED
               AND t.platformPostId IS NOT NULL
               AND t.fireTime >= :since
             ORDER BY t.fireTime DESC
            """)
    List<PostPublishTarget> findPublishedForMetrics(@Param("connectionId") String connectionId,
                                                    @Param("since") OffsetDateTime since);

    /**
     * The APP_MANAGED due poll: targets Conductor still holds whose fire time has arrived. Rows with a
     * null {@code fireTime} are never due — they have not been scheduled yet.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "WHERE t.lane = com.conductor.entity.PublishLane.APP_MANAGED "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PENDING "
            + "AND t.fireTime <= :now "
            + "ORDER BY t.fireTime ASC")
    List<PostPublishTarget> findDueAppManagedTargets(@Param("now") OffsetDateTime now);

    /**
     * The NATIVE hand-off sweep: targets whose fire time falls inside the look-ahead window, so the
     * post can be handed to the platform's own scheduler before it is due to go live.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "WHERE t.lane = com.conductor.entity.PublishLane.NATIVE "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PENDING "
            + "AND t.fireTime <= :windowOpensBefore "
            + "ORDER BY t.fireTime ASC")
    List<PostPublishTarget> findNativeHandoffTargets(@Param("windowOpensBefore") OffsetDateTime windowOpensBefore);

    /**
     * Manual-lane targets whose fire time has arrived and that are still waiting to be surfaced to a human.
     *
     * <p>Deliberately shaped like the two dispatch queries but doing far less: there is no platform to call
     * and no credential to resolve, so "due" here only means the row should stop looking scheduled and start
     * looking like a task. Ordered by fire time so the oldest overdue post is the first one flagged.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "WHERE t.lane = com.conductor.entity.PublishLane.MANUAL "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PENDING "
            + "AND t.fireTime IS NOT NULL AND t.fireTime <= :now "
            + "ORDER BY t.fireTime ASC")
    List<PostPublishTarget> findDueManualTargets(@Param("now") OffsetDateTime now);

    /**
     * Published destinations of one project whose fire time falls inside an insights window, optionally
     * restricted to one platform. The population {@code MarketingInsightsQueryService} groups over — rows
     * with a null {@code fireTime} are handled separately via {@link #findPublishedWithoutFireTime}.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "WHERE t.workItem.project.id = :projectId "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PUBLISHED "
            + "AND (:platform IS NULL OR t.platform = :platform) "
            + "AND t.fireTime IS NOT NULL AND t.fireTime >= :from AND t.fireTime < :to")
    List<PostPublishTarget> findPublishedByFireTimeWindow(@Param("projectId") String projectId,
                                                          @Param("platform") String platform,
                                                          @Param("from") OffsetDateTime from,
                                                          @Param("to") OffsetDateTime to);

    /**
     * Published destinations with no {@code fireTime} recorded (rows that predate the column, or a lane
     * that never set it) — an insights window falls back to each one's first snapshot to decide whether it
     * belongs.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "WHERE t.workItem.project.id = :projectId "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PUBLISHED "
            + "AND (:platform IS NULL OR t.platform = :platform) "
            + "AND t.fireTime IS NULL")
    List<PostPublishTarget> findPublishedWithoutFireTime(@Param("projectId") String projectId,
                                                         @Param("platform") String platform);

    /**
     * Same population as {@link #findPublishedByFireTimeWindow} but with the owning Work Item (and its
     * Project, for the display id) eagerly fetched — the "what works" weekly connector reads each
     * destination's post title/displayId for its top-posts dimension, and a plain lazy load here would
     * be one extra query per destination.
     */
    @Query("SELECT t FROM PostPublishTarget t "
            + "JOIN FETCH t.workItem wi "
            + "JOIN FETCH wi.project "
            + "WHERE wi.project.id = :projectId "
            + "AND t.state = com.conductor.entity.PostPublishTargetState.PUBLISHED "
            + "AND t.fireTime IS NOT NULL AND t.fireTime >= :from AND t.fireTime < :to "
            + "ORDER BY t.fireTime ASC")
    List<PostPublishTarget> findPublishedWithWorkItemByFireTimeWindow(@Param("projectId") String projectId,
                                                                      @Param("from") OffsetDateTime from,
                                                                      @Param("to") OffsetDateTime to);

    /** Whether this project has ever published at least one destination — the gate the "what works"
     *  weekly connector's health check and its feed provisioner both read. */
    boolean existsByWorkItem_Project_IdAndState(String projectId, PostPublishTargetState state);
}
