package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingLinksApi;
import com.conductor.generated.v2.model.CreateTrackedLinkRequest;
import com.conductor.generated.v2.model.LinkFamilyPerformance;
import com.conductor.generated.v2.model.LinkPerformanceResponse;
import com.conductor.generated.v2.model.TrackedLink;
import com.conductor.generated.v2.model.TrackedLinkListResponse;
import com.conductor.marketing.links.TrackedLinkService;
import com.conductor.marketing.links.TrackedLinkService.LinkInput;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Tracked App Store campaign links (bio, creator, email and per-Post) and the downloads they bring, rolled up by
 * family. Any project member reads; writing needs ADMIN or CREATOR. The {@code /api/v2} prefix is applied
 * structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingLinksController implements MarketingLinksApi {

    private final TrackedLinkService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingLinksController(TrackedLinkService service, ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<TrackedLinkListResponse> listTrackedLinks(String projectId, String kind, String workItemId,
                                                                    Integer limit) {
        projectSecurityService.requireProjectAccess(projectId);
        var page = service.list(projectId, kind, workItemId,
                limit == null ? TrackedLinkService.DEFAULT_LIST_LIMIT : limit);
        return ResponseEntity.ok(new TrackedLinkListResponse(page.links().stream().map(MarketingLinksController::toLink)
                .toList(), page.total()));
    }

    @Override
    public ResponseEntity<TrackedLink> createTrackedLink(String projectId, CreateTrackedLinkRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var created = service.create(projectId,
                new LinkInput(request.getKind().getValue(), request.getFamily(), request.getToken(),
                        request.getLabel(), request.getWorkItemId()),
                actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toLink(created));
    }

    @Override
    public ResponseEntity<LinkPerformanceResponse> getTrackedLinkPerformance(String projectId, LocalDate from,
                                                                             LocalDate to) {
        projectSecurityService.requireProjectAccess(projectId);
        var performance = service.performance(projectId, from, to);
        return ResponseEntity.ok(new LinkPerformanceResponse(performance.from(), performance.to(),
                performance.families().stream().map(MarketingLinksController::toFamily).toList()));
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static TrackedLink toLink(com.conductor.marketing.links.TrackedLink l) {
        return new TrackedLink(l.getId(), TrackedLink.KindEnum.fromValue(l.getKind()), l.getFamily(), l.getToken(),
                l.getUrl(), l.getCreatedAt()).workItemId(l.getWorkItemId()).label(l.getLabel());
    }

    private static LinkFamilyPerformance toFamily(TrackedLinkService.FamilyPerformance f) {
        return new LinkFamilyPerformance(f.family(), LinkFamilyPerformance.KindEnum.fromValue(f.kind()), f.tokens(),
                f.belowThreshold()).downloads(f.downloads()).note(f.note()).views(f.views());
    }
}
