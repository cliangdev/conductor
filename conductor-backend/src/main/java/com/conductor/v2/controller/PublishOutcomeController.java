package com.conductor.v2.controller;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.PublishOutcomesApi;
import com.conductor.generated.v2.model.PublishTargetResponse;
import com.conductor.generated.v2.model.RetryPublishResponse;
import com.conductor.service.PublishOutcomeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * COND-23 T6.2 — retrying the publish targets that failed on a Post.
 *
 * <p>Its own resource, and its own operation, because a retry is not an edit of the selection: the set of
 * destinations is unchanged, and what moves is the state of the rows that failed. {@code PUT
 * .../publish-targets} (the set-replace on {@link PublishTargetController}) would be the wrong verb for it
 * and would run the publish-bundle guard, reverting an approval that is still perfectly valid.
 *
 * <p>Everything that decides anything — the membership check, which rows may move, the fresh idempotency
 * key, and the Post's status while the retry is in flight — lives in
 * {@link PublishOutcomeService#retryFailedTargets}, which is also where the roll-up that produced the
 * failure lives. This class only maps the result onto the v2 wire shapes. The {@code /api/v2} prefix is
 * applied structurally by {@code ApiPathConfig} for controllers in this package, so the mapping is bare.
 */
@RestController
public class PublishOutcomeController implements PublishOutcomesApi {

    private final PublishOutcomeService publishOutcomeService;

    public PublishOutcomeController(PublishOutcomeService publishOutcomeService) {
        this.publishOutcomeService = publishOutcomeService;
    }

    @Override
    public ResponseEntity<RetryPublishResponse> retryFailedPublishTargets(String projectId, String workItemId) {
        PublishOutcomeService.RetryResult result =
                publishOutcomeService.retryFailedTargets(projectId, workItemId, currentUser());
        RetryPublishResponse body = new RetryPublishResponse(
                result.post().getId(),
                result.post().getCurrentStatus(),
                result.retried(),
                result.targets().stream().map(PublishOutcomeController::toResponse).toList());
        return ResponseEntity.ok(body);
    }

    private static PublishTargetResponse toResponse(PostPublishTarget target) {
        return new PublishTargetResponse(
                target.getId(),
                target.getWorkItem().getId(),
                PublishTargetResponse.PlatformEnum.fromValue(target.getPlatform()),
                target.getConnectorId(),
                target.getConnectionId(),
                PublishTargetResponse.LaneEnum.fromValue(target.getLane().name()),
                target.getState().name())
                .label(target.getPlatformAccountLabel())
                .platformPostId(target.getPlatformPostId());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
