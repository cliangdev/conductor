package com.conductor.v2.controller;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.PublishOutcomesApi;
import com.conductor.generated.v2.model.CompleteManualPublishRequest;
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

    /**
     * Records a manual target as published by hand. Mirrors the retry operation's shape exactly: every rule
     * — the membership check, the MANUAL-lane refusal, the required permalink, the roll-up — lives in
     * {@link PublishOutcomeService#completeManualTarget}, and this class only maps the result onto the wire.
     *
     * <p>Returns the target as it stands afterwards rather than a bare 204, so a client renders the new
     * state (its PUBLISHED row, its stored permalink, its recorded time) without a second request.
     */
    @Override
    public ResponseEntity<PublishTargetResponse> completeManualPublish(
            String projectId, String workItemId, String targetId, CompleteManualPublishRequest request) {
        publishOutcomeService.completeManualTarget(
                projectId, workItemId, targetId,
                request == null ? null : request.getPermalink(),
                request == null ? null : request.getPublishedAt(),
                currentUser());
        return ResponseEntity.ok(toResponse(
                publishOutcomeService.readTarget(projectId, workItemId, targetId, currentUser())));
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
                PublishTargetResponse.LaneEnum.fromValue(target.getLane().name()),
                target.getState().name())
                // Both null on the MANUAL lane, which publishes through no connector and no account.
                .connectorId(target.getConnectorId())
                .connectionId(target.getConnectionId())
                .label(target.getPlatformAccountLabel())
                .platformPostId(target.getPlatformPostId())
                .permalink(target.getPermalink())
                .errorMessage(target.getErrorMessage())
                .fireTime(target.getFireTime());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
