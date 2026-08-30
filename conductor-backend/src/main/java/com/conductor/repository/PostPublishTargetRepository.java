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

    List<PostPublishTarget> findAllByWorkItemId(String workItemId);

    List<PostPublishTarget> findAllByWorkItemIdAndState(String workItemId, PostPublishTargetState state);

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
}
