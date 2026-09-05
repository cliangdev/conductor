package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.PublishTargetsApi;
import com.conductor.generated.v2.model.PublishConsentResponse;
import com.conductor.generated.v2.model.PublishTargetOption;
import com.conductor.generated.v2.model.PublishTargetResponse;
import com.conductor.generated.v2.model.PublishTargetSelection;
import com.conductor.generated.v2.model.RecordPublishConsentRequest;
import com.conductor.generated.v2.model.ReplacePublishTargetsRequest;
import com.conductor.service.PublishConsentService;
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
    private final PublishConsentService publishConsentService;

    public PublishTargetController(PublishTargetService publishTargetService,
                                   PublishConsentService publishConsentService) {
        this.publishTargetService = publishTargetService;
        this.publishConsentService = publishConsentService;
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
        return ResponseEntity.ok(PublishTargetResponses.from(
                publishTargetService.listSelectedTargetViews(projectId, workItemId, currentUser())));
    }

    @Override
    public ResponseEntity<List<PublishTargetResponse>> replaceWorkItemPublishTargets(
            String projectId, String workItemId, ReplacePublishTargetsRequest request) {
        List<PublishTargetService.TargetSelection> selections =
                (request == null || request.getTargets() == null ? List.<PublishTargetSelection>of()
                        : request.getTargets()).stream()
                        .map(selection -> new PublishTargetService.TargetSelection(
                                selection.getPlatform() == null ? null : selection.getPlatform().getValue(),
                                selection.getConnectionId(),
                                selection.getPublishOptions(),
                                selection.getCaptionOverride(),
                                selection.getAssetIds()))
                        .toList();
        return ResponseEntity.ok(PublishTargetResponses.from(
                publishTargetService.replaceSelection(projectId, workItemId, selections, currentUser())));
    }

    @Override
    public ResponseEntity<PublishConsentResponse> getPublishConsent(String projectId, String workItemId) {
        return ResponseEntity.ok(toResponse(workItemId,
                publishConsentService.readConsent(projectId, workItemId, currentUser())));
    }

    @Override
    public ResponseEntity<PublishConsentResponse> recordPublishConsent(
            String projectId, String workItemId, RecordPublishConsentRequest request) {
        boolean consented = request != null && Boolean.TRUE.equals(request.getConsented());
        return ResponseEntity.ok(toResponse(workItemId,
                publishConsentService.recordConsent(projectId, workItemId, consented, currentUser())));
    }

    private static PublishConsentResponse toResponse(String workItemId,
                                                     PublishConsentService.ConsentState state) {
        return new PublishConsentResponse(
                workItemId,
                state.required(),
                state.valid(),
                PublishConsentResponse.VerdictEnum.fromValue(state.verdict().name()))
                .consentedAt(state.consentedAt())
                .consentedByUserId(state.consentedByUserId())
                .consentedByName(state.consentedByName());
    }

    private static PublishTargetOption toOption(PublishTargetService.TargetOption option) {
        return new PublishTargetOption(
                PublishTargetOption.PlatformEnum.fromValue(option.platform()),
                option.label(),
                PublishTargetOption.LaneEnum.fromValue(option.lane().name()))
                // Both null on a MANUAL option: it is identified by its platform alone, because there is
                // no account behind it and exactly one of it per platform.
                .connectorId(option.connectorId())
                .connectionId(option.connectionId())
                .healthStatus(option.healthStatus())
                .healthMessage(option.healthMessage())
                // Null, not an empty list, when a TikTok connection never cached the creator's levels: the
                // picker has to tell "reconnect this account" apart from a genuinely empty set of choices.
                .privacyLevelOptions(option.privacyLevelOptions())
                .creatorNickname(option.creatorNickname());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
