package com.conductor.v2.controller;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.PublishTargetsApi;
import com.conductor.generated.v2.model.PublishTargetOption;
import com.conductor.generated.v2.model.PublishTargetResponse;
import com.conductor.generated.v2.model.PublishTargetSelection;
import com.conductor.generated.v2.model.ReplacePublishTargetsRequest;
import com.conductor.service.PublishTargetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * COND-23 T3.6 — the publish-target surface: what a project can post to, and what one Post is posting to.
 *
 * <p>Two resources, deliberately separate. {@code GET /projects/{projectId}/publish-targets} is a
 * discovery listing derived from the project's ACTIVE connections and owns no rows; the pair on
 * {@code .../work-items/{workItemId}/publish-targets} reads and replaces the Post's actual selection. The
 * guidelines' registry-vs-instance split (api-guidelines §5) is why the first is its own operation rather
 * than a query flag on the second.
 *
 * <p>All membership checks, the derivation, the set-replace diff and the {@code PublishBundleGuard} call
 * live in {@link PublishTargetService}; this class only maps entities onto the v2 wire shapes. The
 * {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers in this package,
 * so the mappings are bare.
 */
@RestController
public class PublishTargetController implements PublishTargetsApi {

    private final PublishTargetService publishTargetService;

    public PublishTargetController(PublishTargetService publishTargetService) {
        this.publishTargetService = publishTargetService;
    }

    @Override
    public ResponseEntity<List<PublishTargetOption>> listProjectPublishTargets(String projectId) {
        List<PublishTargetOption> body = publishTargetService.listAvailableTargets(projectId, currentUser()).stream()
                .map(PublishTargetController::toOption)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<List<PublishTargetResponse>> listWorkItemPublishTargets(String projectId,
                                                                                  String workItemId) {
        return ResponseEntity.ok(toResponses(
                publishTargetService.listSelectedTargets(projectId, workItemId, currentUser())));
    }

    @Override
    public ResponseEntity<List<PublishTargetResponse>> replaceWorkItemPublishTargets(
            String projectId, String workItemId, ReplacePublishTargetsRequest request) {
        List<PublishTargetService.TargetSelection> selections =
                (request == null || request.getTargets() == null ? List.<PublishTargetSelection>of()
                        : request.getTargets()).stream()
                        .map(selection -> new PublishTargetService.TargetSelection(
                                selection.getPlatform() == null ? null : selection.getPlatform().getValue(),
                                selection.getConnectionId()))
                        .toList();
        return ResponseEntity.ok(toResponses(
                publishTargetService.replaceSelection(projectId, workItemId, selections, currentUser())));
    }

    private static List<PublishTargetResponse> toResponses(List<PostPublishTarget> targets) {
        return targets.stream().map(PublishTargetController::toResponse).toList();
    }

    private static PublishTargetOption toOption(PublishTargetService.TargetOption option) {
        return new PublishTargetOption(
                PublishTargetOption.PlatformEnum.fromValue(option.platform()),
                option.connectorId(),
                option.connectionId(),
                option.label(),
                PublishTargetOption.LaneEnum.fromValue(option.lane().name()))
                .healthStatus(option.healthStatus())
                .healthMessage(option.healthMessage());
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
                .platformPostId(target.getPlatformPostId())
                .permalink(target.getPermalink())
                .errorMessage(target.getErrorMessage())
                .fireTime(target.getFireTime());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
