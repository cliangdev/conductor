package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.PublishTargetsApi;
import com.conductor.generated.v2.model.PostFormat;
import com.conductor.generated.v2.model.PublishConsentResponse;
import com.conductor.generated.v2.model.PublishPreflightConsent;
import com.conductor.generated.v2.model.PublishPreflightFinding;
import com.conductor.generated.v2.model.PublishPreflightResponse;
import com.conductor.generated.v2.model.PublishPreflightReview;
import com.conductor.generated.v2.model.PublishPreflightTransition;
import com.conductor.generated.v2.model.PublishTargetOption;
import com.conductor.generated.v2.model.PublishTargetResponse;
import com.conductor.generated.v2.model.PublishTargetSelection;
import com.conductor.generated.v2.model.CreateTikTokConsentRequest;
import com.conductor.generated.v2.model.RecordTikTokConsentRequest;
import com.conductor.generated.v2.model.TikTokConsentChoice;
import com.conductor.generated.v2.model.TikTokConsentCreator;
import com.conductor.generated.v2.model.TikTokConsentPage;
import com.conductor.generated.v2.model.TikTokConsentPost;
import com.conductor.generated.v2.model.TikTokConsentRequestItem;
import com.conductor.generated.v2.model.TikTokConsentRequestResponse;
import com.conductor.generated.v2.model.TikTokConsentRequestStatus;
import com.conductor.generated.v2.model.TikTokConsentSkipped;
import com.conductor.generated.v2.model.TikTokConsentTarget;
import com.conductor.generated.v2.model.RecordPublishConsentRequest;
import com.conductor.generated.v2.model.ReplacePublishTargetsRequest;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.connector.tiktok.TikTokCreatorInfoService;
import com.conductor.service.PublishConsentService;
import com.conductor.service.TikTokConsentRequestService;
import com.conductor.service.PublishTargetService;
import com.conductor.service.publish.PublishFinding;
import com.conductor.service.publish.PublishPreflightService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private final PublishPreflightService publishPreflightService;
    private final TikTokConsentRequestService consentRequestService;

    public PublishTargetController(PublishTargetService publishTargetService,
                                   PublishConsentService publishConsentService,
                                   PublishPreflightService publishPreflightService,
                                   TikTokConsentRequestService consentRequestService) {
        this.publishTargetService = publishTargetService;
        this.publishConsentService = publishConsentService;
        this.publishPreflightService = publishPreflightService;
        this.consentRequestService = consentRequestService;
    }

    @Override
    public ResponseEntity<PublishPreflightResponse> getWorkItemPublishPreflight(String projectId, String workItemId) {
        PublishPreflightService.Preflight preflight =
                publishPreflightService.preflight(projectId, workItemId, currentUser());
        PublishPreflightResponse body = new PublishPreflightResponse(
                preflight.publishing(),
                preflight.ready(),
                preflight.blockers().stream().map(PublishTargetController::toFinding).toList(),
                preflight.warnings().stream().map(PublishTargetController::toFinding).toList(),
                new PublishPreflightConsent(preflight.consent().required(),
                        PublishPreflightConsent.VerdictEnum.fromValue(preflight.consent().verdict().name())),
                new PublishPreflightReview(preflight.review().gated(), preflight.review().assignedReviewers(),
                        preflight.review().satisfied()).reviewerRole(preflight.review().reviewerRole()))
                .earliestFireTime(preflight.earliestFireTime());
        if (preflight.nextTransition() != null) {
            body.nextTransition(new PublishPreflightTransition(preflight.nextTransition().to(),
                    preflight.nextTransition().requiresReview()).label(preflight.nextTransition().label()));
        }
        return ResponseEntity.ok(body);
    }

    private static PublishPreflightFinding toFinding(PublishFinding finding) {
        return new PublishPreflightFinding(finding.code(), finding.message()).targetId(finding.targetId());
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
                                selection.getAssetIds(),
                                selection.getFormat() == null ? null : selection.getFormat().getValue()))
                        .toList();
        return ResponseEntity.ok(PublishTargetResponses.from(
                publishTargetService.replaceSelection(projectId, workItemId, selections, currentUser())));
    }

    @Override
    public ResponseEntity<PublishConsentResponse> getPublishConsent(String projectId, String workItemId) {
        PublishConsentService.RecordedConsent read =
                publishConsentService.readConsentWithEtag(projectId, workItemId, currentUser());
        return ResponseEntity.ok(toResponse(workItemId, read.state()).etag(read.etag()));
    }

    @Override
    public ResponseEntity<PublishConsentResponse> recordPublishConsent(
            String projectId, String workItemId, RecordPublishConsentRequest request) {
        boolean consented = request != null && Boolean.TRUE.equals(request.getConsented());
        User caller = currentUser();
        publishConsentService.recordConsent(projectId, workItemId, consented, caller);
        PublishConsentService.RecordedConsent read =
                publishConsentService.readConsentWithEtag(projectId, workItemId, caller);
        return ResponseEntity.ok(toResponse(workItemId, read.state()).etag(read.etag()));
    }

    @Override
    public ResponseEntity<PublishConsentResponse> recordTikTokConsent(
            String projectId, String workItemId, RecordTikTokConsentRequest request, String ifMatch) {
        PublishConsentService.RecordedConsent recorded = publishConsentService.recordInteractiveConsent(
                projectId, workItemId,
                new PublishConsentService.ConsentWrite(
                        request.getTargets().stream().map(PublishTargetController::toChoice).toList(),
                        request.getDeclarationVersion(), request.getChannel().getValue(),
                        request.getRequestId(), ifMatch));
        return ResponseEntity.ok()
                .eTag(recorded.etag())
                .body(toResponse(workItemId, recorded.state()).etag(recorded.etag()));
    }

    private static PublishConsentService.ConsentChoice toChoice(TikTokConsentChoice choice) {
        return new PublishConsentService.ConsentChoice(choice.getConnectionId(), choice.getPrivacyLevel(),
                choice.getDisableComment(), choice.getDisableDuet(), choice.getDisableStitch(),
                choice.getBrandContentToggle(), choice.getBrandOrganicToggle());
    }

    @Override
    public ResponseEntity<TikTokConsentRequestResponse> createTikTokConsentRequest(
            String projectId, CreateTikTokConsentRequest request) {
        TikTokConsentRequestService.Created created = consentRequestService.create(projectId,
                request == null ? null : request.getWorkItemIds());
        TikTokConsentRequestResponse body = new TikTokConsentRequestResponse(
                created.request().getId(), created.consentUrl(), created.request().getExpiresAt(),
                created.items().stream().map(PublishTargetController::toItem).toList(),
                created.skipped().stream().map(skipped -> new TikTokConsentSkipped(skipped.workItemId(),
                        TikTokConsentSkipped.ReasonEnum.fromValue(skipped.reason()))).toList(),
                created.items().isEmpty());
        return ResponseEntity.status(201).body(body);
    }

    @Override
    public ResponseEntity<TikTokConsentRequestStatus> getTikTokConsentRequest(String projectId, String requestId) {
        TikTokConsentRequestService.Status status = consentRequestService.status(projectId, requestId);
        return ResponseEntity.ok(new TikTokConsentRequestStatus(status.request().getId(), status.consentUrl(),
                status.request().getExpiresAt(), status.expired(), status.complete(),
                status.items().stream().map(PublishTargetController::toItem).toList())
                .completedAt(status.request().getCompletedAt()));
    }

    @Override
    public ResponseEntity<com.conductor.generated.v2.model.TikTokConsentSummary> getTikTokConsentSummary(
            String projectId) {
        return ResponseEntity.ok(new com.conductor.generated.v2.model.TikTokConsentSummary(
                consentRequestService.pendingCount(projectId)));
    }

    @Override
    public ResponseEntity<TikTokConsentPage> getTikTokConsentPage(String projectId, String request) {
        TikTokConsentRequestService.Page page = consentRequestService.page(projectId, request);
        TikTokConsentPage body = new TikTokConsentPage(
                page.posts().stream().map(PublishTargetController::toPost).toList(),
                page.creators().stream().map(PublishTargetController::toCreator).toList());
        if (page.request() != null) {
            body.requestId(page.request().getId()).expiresAt(page.request().getExpiresAt());
        }
        return ResponseEntity.ok(body);
    }

    private static TikTokConsentRequestItem toItem(TikTokConsentRequestService.Item item) {
        return new TikTokConsentRequestItem(item.workItemId(), item.displayId(), item.title(),
                TikTokConsentRequestItem.VerdictEnum.fromValue(item.consent().verdict().name()))
                .consentedAt(item.consent().consentedAt())
                .consentedByUserId(item.consent().consentedByUserId())
                .consentedByName(item.consent().consentedByName());
    }

    private static TikTokConsentPost toPost(TikTokConsentRequestService.PagePost post) {
        return new TikTokConsentPost(post.workItem().getId(), post.displayId(), post.workItem().getTitle(),
                TikTokConsentPost.VerdictEnum.fromValue(post.consent().verdict().name()), post.etag(),
                post.targets().stream().map(PublishTargetController::toConsentTarget).toList())
                .caption(post.workItem().getDescription())
                .scheduledAt(post.workItem().getScheduledFor())
                .consentedAt(post.consent().consentedAt())
                .consentedByUserId(post.consent().consentedByUserId())
                .consentedByName(post.consent().consentedByName());
    }

    private static TikTokConsentTarget toConsentTarget(TikTokConsentRequestService.PageTarget target) {
        TikTokConsentTarget body = new TikTokConsentTarget(target.target().getId(),
                target.target().getConnectionId(), target.target().getFormat(), target.effectiveAssetIds())
                .captionOverride(target.target().getCaptionOverride())
                .effectiveCaption(target.effectiveCaption());
        TikTokConsentRequestService.StoredChoice stored = target.consented();
        if (stored != null) {
            body.consented(new TikTokConsentChoice(target.target().getConnectionId(), stored.privacyLevel(),
                    stored.disableComment(), stored.disableDuet(), stored.disableStitch(),
                    stored.brandContentToggle(), stored.brandOrganicToggle()));
        }
        return body;
    }

    private static TikTokConsentCreator toCreator(TikTokCreatorInfoService.Creator creator) {
        return new TikTokConsentCreator(creator.connectionId(), creator.privacyLevelOptions(),
                creator.commentDisabled(), creator.duetDisabled(), creator.stitchDisabled(), creator.canPost(),
                creator.reconnectRequired())
                .nickname(creator.nickname())
                .username(creator.username())
                .avatarUrl(creator.avatarUrl())
                .maxVideoPostDurationSec(creator.maxVideoPostDurationSec())
                .cannotPostReason(creator.cannotPostReason());
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
                .healthDetail(option.healthDetail())
                .formats(option.formats().stream().map(PostFormat::fromValue).toList())
                // Null, not an empty list, when a TikTok connection never cached the creator's levels: the
                // picker has to tell "reconnect this account" apart from a genuinely empty set of choices.
                .privacyLevelOptions(option.privacyLevelOptions())
                .creatorNickname(option.creatorNickname())
                .commentDisabled(option.commentDisabled())
                .duetDisabled(option.duetDisabled())
                .stitchDisabled(option.stitchDisabled())
                .maxVideoPostDurationSec(option.maxVideoPostDurationSec())
                .optionKeys(option.optionKeys());
    }

    /**
     * The calling user. A project API key or run token authenticates with a project id as its principal,
     * not a {@link User}; those used to fail here with a {@code ClassCastException} and surface as a 500.
     * They are a credential this endpoint does not serve, which is a 403.
     */
    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new ForbiddenException("This needs a signed-in user; project keys and run tokens cannot do it");
        }
        return user;
    }
}
