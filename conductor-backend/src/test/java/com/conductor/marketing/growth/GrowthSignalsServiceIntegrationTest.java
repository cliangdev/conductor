package com.conductor.marketing.growth;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.growth.GrowthSignalsService.CompetitorResult;
import com.conductor.marketing.growth.GrowthSignalsService.MomentInput;
import com.conductor.marketing.growth.GrowthSignalsService.SurfaceInput;
import com.conductor.marketing.growth.GrowthSignalsService.View;
import com.conductor.marketing.growth.MomentEvaluator.MomentView;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.conductor.marketing.growth.GrowthSignalsFixture.appId;
import static com.conductor.marketing.growth.GrowthSignalsFixture.date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Moments, ratings, reviews, competitor watch and tracked keywords against real Postgres. */
class GrowthSignalsServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate THANKSGIVING = date("2026-11-26");
    private static final LocalDate SURFACE_DUE = date("2026-11-12");

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private MarketingMomentRepository momentRepository;
    @Autowired private MarketingMomentSurfaceRepository surfaceRepository;
    @Autowired private MarketingCompetitorRepository competitorRepository;
    @Autowired private MarketingTrackedKeywordRepository trackedKeywordRepository;
    @Autowired private MarketingAppSnapshotRepository snapshotRepository;
    @Autowired private MarketingReviewRepository reviewRepository;
    @Autowired private GrowthSignalsSourceRepository sourceRepository;
    @Autowired private TrackedKeywordSource trackedKeywordSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private GrowthSignalsFixture fixture;
    private User owner;
    private Project project;

    @BeforeEach
    void setUp() {
        fixture = new GrowthSignalsFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                workItemRepository);
        owner = fixture.user();
        project = fixture.project(owner);
    }

    private GrowthSignalsService service(String today) {
        return new GrowthSignalsService(momentRepository, surfaceRepository, competitorRepository,
                trackedKeywordRepository, snapshotRepository, reviewRepository, sourceRepository, trackedKeywordSource,
                workItemRepository, objectMapper, Clock.fixed(date(today).atStartOfDay().toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC));
    }

    private MomentInput thanksgiving(SurfaceInput... surfaces) {
        return new MomentInput("Thanksgiving", THANKSGIVING, null, List.of(surfaces));
    }

    private static SurfaceInput promoText() {
        return new SurfaceInput("App Store promotional text", SURFACE_DUE);
    }

    private MomentView setThanksgiving(GrowthSignalsService service, SurfaceInput... surfaces) {
        return service.upsertMoment(project.getId(), "thanksgiving-2026", thanksgiving(surfaces), owner.getId());
    }

    private void snapshot(String appId, String week, String price, String rating, Long count, String version,
                          String title, String hash, String ranksJson) {
        snapshotIn("us", appId, week, price, rating, count, version, title, hash, ranksJson);
    }

    private void snapshotIn(String country, String appId, String week, String price, String rating, Long count,
                            String version, String title, String hash, String ranksJson) {
        snapshotRepository.insertIfAbsent(UUID.randomUUID().toString(), project.getId(), appId, date(week), country,
                price == null ? null : new BigDecimal(price), "USD", rating == null ? null : new BigDecimal(rating),
                count, version, title, null, hash, ranksJson);
    }

    // ---- moments --------------------------------------------------------------------------------------------

    @Test
    void aSurfacePastDueWithNoChangeWarnsOnTheDayAfterAndNotOnTheDueDate() {
        setThanksgiving(service("2026-11-01"), promoText());

        View onTheDay = service("2026-11-12").view(project.getId());
        assertThat(onTheDay.moments()).hasSize(1);
        assertThat(onTheDay.moments().get(0).surfaces().get(0).warning()).isNull();
        assertThat(onTheDay.moments().get(0).status()).isEqualTo("upcoming");

        View dayAfter = service("2026-11-13").view(project.getId());
        MomentView moment = dayAfter.moments().get(0);
        assertThat(moment.status()).isEqualTo("warning");
        assertThat(moment.surfaces().get(0).warning()).isEqualTo(
                "App Store promotional text for Thanksgiving was due 2026-11-12 and no change is recorded");
    }

    @Test
    void aMomentIsStoredWithTheDefaultLeadOf21DaysAndItsSurfacesInDueOrder() {
        MomentView stored = setThanksgiving(service("2026-10-01"),
                new SurfaceInput("TikTok post", date("2026-11-20")), promoText());

        assertThat(stored.leadDays()).isEqualTo(21);
        assertThat(stored.date()).isEqualTo(THANKSGIVING);
        assertThat(stored.surfaces()).extracting(MomentEvaluator.SurfaceView::surface)
                .containsExactly("App Store promotional text", "TikTok post");
        assertThat(stored.status()).isEqualTo("scheduled");
    }

    @Test
    void linkingAChangeClearsTheWarningAndShowsItsDisplayId() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        assertThat(before.status()).isEqualTo("warning");
        WorkItem change = fixture.workItem(project, owner, "MARKETING_CHANGES");

        MomentView after = service.linkChange(project.getId(), "thanksgiving-2026", before.surfaces().get(0).id(),
                change.getId());

        assertThat(after.surfaces().get(0).warning()).isNull();
        assertThat(after.surfaces().get(0).changeWorkItemId()).isEqualTo(change.getId());
        assertThat(after.surfaces().get(0).changeDisplayId()).isEqualTo(project.getKey() + "-" + change.getSequenceNumber());
        assertThat(after.status()).isNotEqualTo("warning");
        assertThat(service.view(project.getId()).moments().get(0).surfaces().get(0).warning()).isNull();
    }

    @Test
    void aChangeCanBeLinkedByDisplayId() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        WorkItem change = fixture.workItem(project, owner, "MARKETING_CHANGES");

        MomentView after = service.linkChange(project.getId(), "thanksgiving-2026", before.surfaces().get(0).id(),
                project.getKey() + "-" + change.getSequenceNumber());

        assertThat(after.surfaces().get(0).changeWorkItemId()).isEqualTo(change.getId());
    }

    @Test
    void aWorkItemFromAnotherProjectIsRefused() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        Project other = fixture.project(owner);
        WorkItem foreign = fixture.workItem(other, owner, "MARKETING_CHANGES");
        String surfaceId = before.surfaces().get(0).id();

        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026", surfaceId, foreign.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("MARKETING_CHANGES");
        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026", surfaceId,
                other.getKey() + "-" + foreign.getSequenceNumber()))
                .isInstanceOf(BusinessException.class);
        assertThat(service.view(project.getId()).moments().get(0).status()).isEqualTo("warning");
    }

    @Test
    void aWorkItemOfAnotherWorkflowIsRefused() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        WorkItem post = fixture.workItem(project, owner, "MARKETING");

        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026",
                before.surfaces().get(0).id(), post.getId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void anUnknownWorkItemOrBlankIdIsRefusedAndAnUnknownMomentOrSurfaceIsNotFound() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        String surfaceId = before.surfaces().get(0).id();

        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026", surfaceId,
                UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026", surfaceId, " "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.linkChange(project.getId(), "no-such-moment", surfaceId, "x"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026", UUID.randomUUID().toString(),
                "x")).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aSurfaceOfAnotherProjectsMomentCannotBeLinkedThroughThisOne() {
        GrowthSignalsService service = service("2026-11-13");
        setThanksgiving(service, promoText());
        Project other = fixture.project(owner);
        MomentView foreign = service.upsertMoment(other.getId(), "thanksgiving-2026", thanksgiving(promoText()),
                owner.getId());
        WorkItem change = fixture.workItem(project, owner, "MARKETING_CHANGES");

        assertThatThrownBy(() -> service.linkChange(project.getId(), "thanksgiving-2026",
                foreign.surfaces().get(0).id(), change.getId())).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void settingTheMomentAgainKeepsALinkedSurfaceAndDropsOrAddsTheRest() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView first = setThanksgiving(service, promoText(), new SurfaceInput("Email", date("2026-11-18")));
        WorkItem change = fixture.workItem(project, owner, "MARKETING_CHANGES");
        String promoId = first.surfaces().stream().filter(s -> s.surface().startsWith("App Store"))
                .findFirst().orElseThrow().id();
        service.linkChange(project.getId(), "thanksgiving-2026", promoId, change.getId());

        MomentView second = setThanksgiving(service,
                new SurfaceInput("App Store promotional text", date("2026-11-14")),
                new SurfaceInput("TikTok post", date("2026-11-20")));

        assertThat(second.surfaces()).extracting(MomentEvaluator.SurfaceView::surface)
                .containsExactly("App Store promotional text", "TikTok post");
        MomentEvaluator.SurfaceView kept = second.surfaces().get(0);
        assertThat(kept.id()).isEqualTo(promoId);
        assertThat(kept.dueOn()).isEqualTo(date("2026-11-14"));
        assertThat(kept.changeWorkItemId()).isEqualTo(change.getId());
        assertThat(momentRepository.findByProjectIdAndMomentKey(project.getId(), "thanksgiving-2026")).isPresent();
        assertThat(service.view(project.getId()).moments()).hasSize(1);
    }

    @Test
    void deletingTheLinkedWorkItemBringsTheWarningBack() {
        GrowthSignalsService service = service("2026-11-13");
        MomentView before = setThanksgiving(service, promoText());
        WorkItem change = fixture.workItem(project, owner, "MARKETING_CHANGES");
        service.linkChange(project.getId(), "thanksgiving-2026", before.surfaces().get(0).id(), change.getId());

        workItemRepository.deleteById(change.getId());
        workItemRepository.flush();

        MomentView after = service.view(project.getId()).moments().get(0);
        assertThat(after.surfaces().get(0).changeWorkItemId()).isNull();
        assertThat(after.status()).isEqualTo("warning");
    }

    @Test
    void momentInputIsValidated() {
        GrowthSignalsService service = service("2026-11-01");
        String pid = project.getId();

        assertThatThrownBy(() -> service.upsertMoment(pid, "Bad Key!", thanksgiving(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", new MomentInput(" ", THANKSGIVING, null, List.of()), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", new MomentInput("N", null, null, List.of()), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", new MomentInput("N", THANKSGIVING, -1, List.of()), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", new MomentInput("N", THANKSGIVING, null, null), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", thanksgiving(promoText(),
                new SurfaceInput("app store PROMOTIONAL text", SURFACE_DUE)), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", thanksgiving(new SurfaceInput("Email", null)), null))
                .isInstanceOf(BusinessException.class);
        List<SurfaceInput> tooMany = IntStream.range(0, 26)
                .mapToObj(i -> new SurfaceInput("Surface " + i, SURFACE_DUE)).toList();
        assertThatThrownBy(() -> service.upsertMoment(pid, "k", new MomentInput("N", THANKSGIVING, null, tooMany), null))
                .isInstanceOf(BusinessException.class);
        assertThat(momentRepository.findByProjectIdOrderByMomentDateAscMomentKeyAsc(pid)).isEmpty();
    }

    @Test
    void aMomentKeyIsCaseInsensitiveAndMomentsOfOtherProjectsAreSeparate() {
        GrowthSignalsService service = service("2026-11-01");
        service.upsertMoment(project.getId(), "Thanksgiving-2026", thanksgiving(promoText()), owner.getId());
        service.upsertMoment(project.getId(), "thanksgiving-2026", thanksgiving(), owner.getId());
        Project other = fixture.project(owner);

        assertThat(service.view(project.getId()).moments()).hasSize(1);
        assertThat(service.view(project.getId()).moments().get(0).surfaces()).isEmpty();
        assertThat(service.view(other.getId()).moments()).isEmpty();
    }

    // ---- ratings --------------------------------------------------------------------------------------------

    @Test
    void weeklyRatingsGrowthIsTheCountDeltaBetweenTheLatestTwoSnapshots() {
        String app = appId();
        fixture.appStoreConnect(project, app, null);
        snapshot(app, "2026-10-19", "0.00", "4.5", 100L, "1.0", "Rexipe", "h", null);
        snapshot(app, "2026-10-26", "0.00", "4.6", 120L, "1.1", "Rexipe", "h", null);
        snapshot(app, "2026-11-02", "0.00", "4.6", 135L, "1.1", "Rexipe", "h", null);

        View view = service("2026-11-04").view(project.getId());

        var ours = view.ratings().ours();
        assertThat(ours.appId()).isEqualTo(app);
        assertThat(ours.latest().ratingCount()).isEqualTo(135L);
        assertThat(ours.latest().capturedOn()).isEqualTo(date("2026-11-02"));
        assertThat(ours.previous().ratingCount()).isEqualTo(120L);
        assertThat(ours.weeklyGrowth()).isEqualTo(15L);
        assertThat(ours.name()).isEqualTo("Rexipe");
    }

    @Test
    void oneSnapshotGivesNoWeeklyGrowthRatherThanZero() {
        String app = appId();
        fixture.appStoreConnect(project, app, null);
        snapshot(app, "2026-11-02", "0.00", "4.6", 135L, "1.1", "Rexipe", "h", null);

        var ours = service("2026-11-04").view(project.getId()).ratings().ours();

        assertThat(ours.latest().ratingCount()).isEqualTo(135L);
        assertThat(ours.previous()).isNull();
        assertThat(ours.weeklyGrowth()).isNull();
    }

    @Test
    void aStorefrontChangeBetweenTwoSnapshotsGivesNoGrowthAndNoChangeList() {
        String rival = appId();
        GrowthSignalsService service = service("2026-11-12");
        service.addCompetitor(project.getId(), rival, "Rival", "gb");
        snapshotIn("us", rival, "2026-11-02", "0.99", "4.5", 100L, "1.0", "Rival", "h1", null);
        snapshotIn("gb", rival, "2026-11-09", "1.49", "4.0", 7L, "1.1", "Rival", "h2", null);

        View view = service.view(project.getId());

        var rating = view.ratings().competitors().get(0);
        assertThat(rating.latest().country()).isEqualTo("gb");
        assertThat(rating.previous().country()).isEqualTo("us");
        assertThat(rating.weeklyGrowth()).isNull();
        assertThat(view.competitorChanges()).isEmpty();
    }

    @Test
    void anAppWithNoSnapshotYetHasNoLatestAndNoGrowth() {
        String app = appId();
        fixture.appStoreConnect(project, app, "gb");

        var ours = service("2026-11-04").view(project.getId()).ratings().ours();

        assertThat(ours.appId()).isEqualTo(app);
        assertThat(ours.country()).isEqualTo("gb");
        assertThat(ours.latest()).isNull();
        assertThat(ours.weeklyGrowth()).isNull();
    }

    @Test
    void aProjectWithoutAnAppStoreConnectionHasNoOwnApp() {
        View view = service("2026-11-04").view(project.getId());

        assertThat(view.ratings().ours()).isNull();
        assertThat(view.reviews().ours()).isEmpty();
        assertThat(view.ratings().competitors()).isEmpty();
        assertThat(view.moments()).isEmpty();
        assertThat(view.competitorChanges()).isEmpty();
        assertThat(view.keywordPresence()).isEmpty();
    }

    @Test
    void anAppleFieldThatWasNotReturnedStaysNullInTheSnapshot() {
        String app = appId();
        fixture.appStoreConnect(project, app, null);
        snapshot(app, "2026-11-02", null, null, null, null, null, null, null);

        var latest = service("2026-11-04").view(project.getId()).ratings().ours().latest();

        assertThat(latest.price()).isNull();
        assertThat(latest.ratingAvg()).isNull();
        assertThat(latest.ratingCount()).isNull();
        assertThat(latest.version()).isNull();
    }

    // ---- reviews --------------------------------------------------------------------------------------------

    private void review(String app, String id, int rating, String at) {
        reviewRepository.insertIfAbsent(UUID.randomUUID().toString(), project.getId(), app, id, rating, "t" + id,
                "body " + id, "author", at == null ? null : OffsetDateTime.parse(at));
    }

    @Test
    void reviewsShowTheLatestTenOfOursAndFiveOfEachCompetitorNewestFirst() {
        String ours = appId();
        String rival = appId();
        fixture.appStoreConnect(project, ours, null);
        GrowthSignalsService service = service("2026-11-04");
        service.addCompetitor(project.getId(), rival, "Rival", null);
        for (int i = 1; i <= 12; i++) {
            review(ours, "o" + i, 5, "2026-10-%02dT10:00:00Z".formatted(i));
            review(rival, "r" + i, 3, "2026-10-%02dT10:00:00Z".formatted(i));
        }
        review(ours, "undated", 1, null);

        View view = service.view(project.getId());

        assertThat(view.reviews().ours()).hasSize(10);
        assertThat(view.reviews().ours().get(0).title()).isEqualTo("to12");
        assertThat(view.reviews().ours()).extracting(GrowthSignalsService.ReviewView::title).doesNotContain("tundated");
        assertThat(view.reviews().competitors()).hasSize(1);
        assertThat(view.reviews().competitors().get(0).appId()).isEqualTo(rival);
        assertThat(view.reviews().competitors().get(0).reviews()).hasSize(5);
        assertThat(view.reviews().competitors().get(0).reviews().get(0).title()).isEqualTo("tr12");
    }

    // ---- competitor watch -----------------------------------------------------------------------------------

    @Test
    void competitorChangesListPriceAndVersionBetweenTheLastTwoSnapshots() {
        String rival = appId();
        GrowthSignalsService service = service("2026-11-04");
        service.addCompetitor(project.getId(), rival, "Rival", "us");
        snapshot(rival, "2026-10-26", "0.99", "4.5", 100L, "1.0", "Rival", "h1", null);
        snapshot(rival, "2026-11-02", "1.99", "4.5", 100L, "1.1", "Rival", "h1", null);

        View view = service.view(project.getId());

        assertThat(view.competitorChanges()).hasSize(1);
        assertThat(view.competitorChanges().get(0).appId()).isEqualTo(rival);
        assertThat(view.competitorChanges().get(0).name()).isEqualTo("Rival");
        assertThat(view.competitorChanges().get(0).changes()).containsExactly("price 0.99 -> 1.99", "version 1.0 -> 1.1");
        assertThat(view.ratings().competitors().get(0).weeklyGrowth()).isEqualTo(0L);
    }

    @Test
    void aCompetitorThatDidNotChangeIsNotListed() {
        String rival = appId();
        GrowthSignalsService service = service("2026-11-04");
        service.addCompetitor(project.getId(), rival, "Rival", "us");
        snapshot(rival, "2026-10-26", "0.99", "4.5", 100L, "1.0", "Rival", "h1", null);
        snapshot(rival, "2026-11-02", "0.99", "4.5", 100L, "1.0", "Rival", "h1", null);

        assertThat(service.view(project.getId()).competitorChanges()).isEmpty();
    }

    @Test
    void aCompetitorWithOneSnapshotHasNoChangesAndNoGrowth() {
        String rival = appId();
        GrowthSignalsService service = service("2026-11-04");
        service.addCompetitor(project.getId(), rival, "Rival", "us");
        snapshot(rival, "2026-11-02", "0.99", "4.5", 100L, "1.0", "Rival", "h1", null);

        View view = service.view(project.getId());

        assertThat(view.competitorChanges()).isEmpty();
        assertThat(view.ratings().competitors().get(0).weeklyGrowth()).isNull();
    }

    @Test
    void competitorsAreAddedUpdatedRemovedAndAddedBack() {
        String rival = appId();
        GrowthSignalsService service = service("2026-11-04");

        CompetitorResult added = service.addCompetitor(project.getId(), rival, "Rival", "GB");
        assertThat(added.created()).isTrue();
        assertThat(added.competitor().getCountry()).isEqualTo("gb");
        assertThat(added.competitor().isActive()).isTrue();

        CompetitorResult renamed = service.addCompetitor(project.getId(), rival, "Rival Pro", null);
        assertThat(renamed.created()).isFalse();
        assertThat(renamed.competitor().getName()).isEqualTo("Rival Pro");
        assertThat(renamed.competitor().getCountry()).isEqualTo("gb");
        assertThat(competitorRepository.findByProjectIdAndActiveTrueOrderByNameAscAppIdAsc(project.getId())).hasSize(1);

        service.removeCompetitor(project.getId(), rival);
        service.removeCompetitor(project.getId(), rival);
        service.removeCompetitor(project.getId(), appId());
        assertThat(service.view(project.getId()).ratings().competitors()).isEmpty();
        assertThat(competitorRepository.findByProjectIdAndAppId(project.getId(), rival)).isPresent();

        CompetitorResult back = service.addCompetitor(project.getId(), rival, "Rival Pro", null);
        assertThat(back.created()).isFalse();
        assertThat(service.view(project.getId()).ratings().competitors()).extracting(GrowthSignalsService.RatingView::appId)
                .containsExactly(rival);
    }

    @Test
    void aCompetitorDefaultsToTheCountryOfOurApp() {
        fixture.appStoreConnect(project, appId(), "de");

        CompetitorResult added = service("2026-11-04").addCompetitor(project.getId(), appId(), "Rival", null);

        assertThat(added.competitor().getCountry()).isEqualTo("de");
    }

    @Test
    void competitorInputIsValidatedAndOurOwnAppIsNotACompetitor() {
        GrowthSignalsService service = service("2026-11-04");
        String own = appId();
        fixture.appStoreConnect(project, own, null);
        String pid = project.getId();

        assertThatThrownBy(() -> service.addCompetitor(pid, "12ab", "Rival", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addCompetitor(pid, "https://evil.example/1", "Rival", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addCompetitor(pid, appId(), " ", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addCompetitor(pid, appId(), "Rival", "usa")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addCompetitor(pid, own, "Me", null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("own app");
        assertThatThrownBy(() -> service.removeCompetitor(pid, "x1")).isInstanceOf(BusinessException.class);
        assertThat(competitorRepository.findByProjectIdAndActiveTrueOrderByNameAscAppIdAsc(pid)).isEmpty();
    }

    /** The feed's upsert is a modifying query, so it needs a transaction around it. */
    private void putAso(String connectionId, String dimensionKey, String day) {
        transactionTemplate.executeWithoutResult(status -> dailyRepository.upsert(UUID.randomUUID().toString(),
                project.getId(), connectionId, "product-metrics", "PRODUCT", "organic_rank", dimensionKey, date(day),
                BigDecimal.ONE, false));
    }

    // ---- keyword presence and tracked keywords --------------------------------------------------------------

    @Test
    void keywordPresenceComesFromTheLatestSnapshotsAndNullMeansNotInTheFirst50() {
        String ours = appId();
        String rival = appId();
        fixture.appStoreConnect(project, ours, null);
        GrowthSignalsService service = service("2026-11-04");
        service.addCompetitor(project.getId(), rival, "Rival", "us");
        snapshot(ours, "2026-10-26", "0.00", "4.5", 100L, "1.0", "Rexipe", "h", "{\"recipe app\": 9}");
        snapshot(ours, "2026-11-02", "0.00", "4.5", 100L, "1.0", "Rexipe", "h",
                "{\"recipe app\": 3, \"meal planner\": null}");
        snapshot(rival, "2026-11-02", "0.00", "4.5", 100L, "1.0", "Rival", "h",
                "{\"recipe app\": 1, \"meal planner\": 7}");

        var presence = service.view(project.getId()).keywordPresence();

        assertThat(presence).extracting(GrowthSignalsService.KeywordPresence::keyword)
                .containsExactly("meal planner", "recipe app");
        var meal = presence.get(0);
        assertThat(meal.ours()).isNull();
        assertThat(meal.competitors()).containsEntry(rival, 7);
        var recipe = presence.get(1);
        assertThat(recipe.ours()).isEqualTo(3);
        assertThat(recipe.competitors()).containsEntry(rival, 1);
    }

    @Test
    void trackedKeywordsAreReplacedNormalisedAndReadBack() {
        GrowthSignalsService service = service("2026-11-04");
        String pid = project.getId();

        var stored = service.setTrackedKeywords(pid, List.of("  Recipe  App ", "recipe app", "Meal Planner"), "US");
        assertThat(stored.keywords()).containsExactly("recipe app", "meal planner");
        assertThat(stored.country()).isEqualTo("us");
        assertThat(stored.source()).isEqualTo("PROJECT_LIST");
        assertThat(service.view(pid).trackedKeywords().keywords()).containsExactlyInAnyOrder("recipe app", "meal planner");

        service.setTrackedKeywords(pid, List.of("meal planner", "grocery list"), null);
        assertThat(service.view(pid).trackedKeywords().keywords()).containsExactlyInAnyOrder("meal planner", "grocery list");

        service.setTrackedKeywords(pid, List.of(), null);
        assertThat(service.view(pid).trackedKeywords().keywords()).isEmpty();
        assertThat(trackedKeywordRepository.findByProjectIdOrderByKeywordAscCountryAsc(pid)).isEmpty();
    }

    @Test
    void trackedKeywordInputIsValidated() {
        GrowthSignalsService service = service("2026-11-04");
        String pid = project.getId();
        List<String> tooMany = IntStream.range(0, 26).mapToObj(i -> "keyword " + i).toList();

        assertThatThrownBy(() -> service.setTrackedKeywords(pid, tooMany, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setTrackedKeywords(pid, List.of(" "), null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setTrackedKeywords(pid, List.of("x".repeat(101)), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setTrackedKeywords(pid, List.of("ok"), "usa")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setTrackedKeywords(pid, null, null)).isInstanceOf(BusinessException.class);
        assertThat(service.setTrackedKeywords(pid, IntStream.range(0, 25).mapToObj(i -> "keyword " + i).toList(), null)
                .keywords()).hasSize(25);
    }

    @Test
    void theProductFeedsAsoKeywordsTakePrecedenceOverTheProjectList() {
        GrowthSignalsService service = service("2026-11-04");
        service.setTrackedKeywords(project.getId(), List.of("listed keyword"), "us");
        String connectionId = fixture.connect(project, "product-metrics", "{}");
        putAso(connectionId, "aso:fed keyword", "2026-11-01");
        putAso(connectionId, "aso:other fed", "2026-11-02");

        var tracked = service.view(project.getId()).trackedKeywords();

        assertThat(tracked.source()).isEqualTo("PRODUCT_FEED");
        assertThat(tracked.keywords()).containsExactly("fed keyword", "other fed");
    }

    @Test
    void asoRowsOlderThanTheLookbackDoNotOverrideTheProjectList() {
        GrowthSignalsService service = service("2026-11-04");
        service.setTrackedKeywords(project.getId(), List.of("listed keyword"), "us");
        String connectionId = fixture.connect(project, "product-metrics", "{}");
        putAso(connectionId, "aso:stale keyword", "2026-05-01");

        var tracked = service.view(project.getId()).trackedKeywords();

        assertThat(tracked.source()).isEqualTo("PROJECT_LIST");
        assertThat(tracked.keywords()).containsExactly("listed keyword");
    }

    @Test
    void theKeywordListIsCappedAt25() {
        GrowthSignalsService service = service("2026-11-04");
        String connectionId = fixture.connect(project, "product-metrics", "{}");
        for (int i = 0; i < 30; i++) {
            putAso(connectionId, "aso:kw %02d".formatted(i), "2026-11-01");
        }

        assertThat(service.view(project.getId()).trackedKeywords().keywords()).hasSize(TrackedKeywordSource.MAX_KEYWORDS);
    }
}
