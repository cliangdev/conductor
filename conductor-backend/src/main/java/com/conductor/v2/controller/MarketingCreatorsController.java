package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingCreatorsApi;
import com.conductor.generated.v2.model.CreatorResults;
import com.conductor.generated.v2.model.CreatorResultsCreator;
import com.conductor.generated.v2.model.CreatorResultsLinkDownloads;
import com.conductor.generated.v2.model.CreatorResultsOfferRedemptions;
import com.conductor.generated.v2.model.CreatorResultsPost;
import com.conductor.generated.v2.model.CreatorResultsWindow;
import com.conductor.marketing.creators.CreatorResultsService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * What one creator has produced: its Posts, the downloads of its campaign links and the redemptions of its offer
 * code. Any project member reads. The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingCreatorsController implements MarketingCreatorsApi {

    private final CreatorResultsService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingCreatorsController(CreatorResultsService service, ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<CreatorResults> getCreatorResults(String projectId, String workItemId, LocalDate from,
                                                            LocalDate to) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toResponse(service.results(projectId, workItemId, from, to)));
    }

    // ---- mapping ------------------------------------------------------------------------------------

    static CreatorResults toResponse(CreatorResultsService.Results r) {
        return new CreatorResults(
                new CreatorResultsWindow(r.window().from(), r.window().to()),
                new CreatorResultsCreator(r.creator().workItemId(), r.creator().displayId(), r.creator().status())
                        .handle(r.creator().handle()),
                r.postCount(),
                r.posts().stream()
                        .map(p -> new CreatorResultsPost(p.workItemId(), p.displayId(), p.title(), p.status())
                                .views(p.views()))
                        .toList(),
                new CreatorResultsLinkDownloads(r.linkDownloads().tokens(), r.linkDownloads().belowThreshold())
                        .downloads(r.linkDownloads().downloads()).note(r.linkDownloads().note()),
                new CreatorResultsOfferRedemptions(r.offerRedemptions().code())
                        .value(r.offerRedemptions().value()).note(r.offerRedemptions().note()));
    }
}
