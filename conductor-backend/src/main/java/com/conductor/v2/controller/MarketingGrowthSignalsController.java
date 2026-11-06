package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingGrowthSignalsApi;
import com.conductor.generated.v2.model.AddGrowthCompetitorRequest;
import com.conductor.generated.v2.model.GrowthAppRating;
import com.conductor.generated.v2.model.GrowthAppReviews;
import com.conductor.generated.v2.model.GrowthCompetitor;
import com.conductor.generated.v2.model.GrowthCompetitorChange;
import com.conductor.generated.v2.model.GrowthKeywordPresence;
import com.conductor.generated.v2.model.GrowthMoment;
import com.conductor.generated.v2.model.GrowthRatings;
import com.conductor.generated.v2.model.GrowthReview;
import com.conductor.generated.v2.model.GrowthReviews;
import com.conductor.generated.v2.model.GrowthSignals;
import com.conductor.generated.v2.model.GrowthSnapshot;
import com.conductor.generated.v2.model.GrowthSurface;
import com.conductor.generated.v2.model.GrowthTrackedKeywords;
import com.conductor.generated.v2.model.LinkMomentChangeRequest;
import com.conductor.generated.v2.model.MarketingMomentRequest;
import com.conductor.generated.v2.model.SetTrackedKeywordsRequest;
import com.conductor.marketing.growth.CompetitorChanges.SnapshotView;
import com.conductor.marketing.growth.GrowthSignalsService;
import com.conductor.marketing.growth.MomentEvaluator.MomentView;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The growth signals: seasonal moments with their surfaces, the weekly ratings program and the competitor watch.
 * Any project member reads; every write needs ADMIN or CREATOR. The {@code /api/v2} prefix is applied
 * structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingGrowthSignalsController implements MarketingGrowthSignalsApi {

    private final GrowthSignalsService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingGrowthSignalsController(GrowthSignalsService service,
                                            ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<GrowthSignals> getGrowthSignals(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toSignals(service.view(projectId)));
    }

    @Override
    public ResponseEntity<GrowthMoment> setMarketingMoment(String projectId, String key,
                                                           MarketingMomentRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var surfaces = request.getSurfaces().stream()
                .map(s -> new GrowthSignalsService.SurfaceInput(s.getSurface(), s.getDueOn())).toList();
        var moment = service.upsertMoment(projectId, key,
                new GrowthSignalsService.MomentInput(request.getName(), request.getDate(), request.getLeadDays(),
                        surfaces),
                actor.userId());
        return ResponseEntity.ok(toMoment(moment));
    }

    @Override
    public ResponseEntity<GrowthMoment> linkMarketingMomentChange(String projectId, String key, String surfaceId,
                                                                  LinkMomentChangeRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toMoment(service.linkChange(projectId, key, surfaceId, request.getWorkItemId())));
    }

    @Override
    public ResponseEntity<GrowthCompetitor> addMarketingCompetitor(String projectId,
                                                                   AddGrowthCompetitorRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        var result = service.addCompetitor(projectId, request.getAppId(), request.getName(), request.getCountry());
        var c = result.competitor();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new GrowthCompetitor(c.getAppId(), c.getName(), c.getCountry(), c.isActive()));
    }

    @Override
    public ResponseEntity<Void> removeMarketingCompetitor(String projectId, String appId) {
        projectSecurityService.requireProjectEditor(projectId);
        service.removeCompetitor(projectId, appId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<GrowthTrackedKeywords> setMarketingTrackedKeywords(String projectId,
                                                                             SetTrackedKeywordsRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toTracked(service.setTrackedKeywords(projectId, request.getKeywords(),
                request.getCountry())));
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static GrowthSignals toSignals(GrowthSignalsService.View v) {
        return new GrowthSignals(
                v.moments().stream().map(MarketingGrowthSignalsController::toMoment).toList(),
                new GrowthRatings(v.ratings().competitors().stream()
                        .map(MarketingGrowthSignalsController::toRating).toList())
                        .ours(v.ratings().ours() == null ? null : toRating(v.ratings().ours())),
                new GrowthReviews(
                        v.reviews().ours().stream().map(MarketingGrowthSignalsController::toReview).toList(),
                        v.reviews().competitors().stream().map(MarketingGrowthSignalsController::toAppReviews)
                                .toList()),
                v.competitorChanges().stream()
                        .map(c -> new GrowthCompetitorChange(c.appId(), c.name(), c.changes())).toList(),
                v.keywordPresence().stream()
                        .map(k -> new GrowthKeywordPresence(k.keyword(), k.competitors()).ours(k.ours())).toList(),
                toTracked(v.trackedKeywords()));
    }

    private static GrowthMoment toMoment(MomentView m) {
        return new GrowthMoment(m.key(), m.name(), m.date(), m.leadDays(), m.status(),
                m.surfaces().stream().map(s -> new GrowthSurface(s.id(), s.surface(), s.dueOn())
                        .changeWorkItemId(s.changeWorkItemId()).changeDisplayId(s.changeDisplayId())
                        .warning(s.warning())).toList());
    }

    private static GrowthAppRating toRating(GrowthSignalsService.RatingView r) {
        return new GrowthAppRating(r.appId(), r.country()).name(r.name()).latest(toSnapshot(r.latest()))
                .previous(toSnapshot(r.previous())).weeklyGrowth(r.weeklyGrowth());
    }

    private static GrowthSnapshot toSnapshot(SnapshotView s) {
        if (s == null) {
            return null;
        }
        return new GrowthSnapshot(s.capturedOn()).country(s.country())
                .price(s.price() == null ? null : s.price().doubleValue()).currency(s.currency())
                .ratingAvg(s.ratingAvg() == null ? null : s.ratingAvg().doubleValue())
                .ratingCount(s.ratingCount()).version(s.version()).title(s.title()).subtitle(s.subtitle());
    }

    private static GrowthReview toReview(GrowthSignalsService.ReviewView r) {
        return new GrowthReview().rating(r.rating()).title(r.title()).body(r.body()).author(r.author())
                .reviewedAt(r.reviewedAt());
    }

    private static GrowthAppReviews toAppReviews(GrowthSignalsService.AppReviews a) {
        List<GrowthReview> reviews = a.reviews().stream().map(MarketingGrowthSignalsController::toReview).toList();
        return new GrowthAppReviews(a.appId(), a.name(), reviews);
    }

    private static GrowthTrackedKeywords toTracked(GrowthSignalsService.TrackedKeywords t) {
        return new GrowthTrackedKeywords(t.source(), t.country(), t.keywords());
    }
}
