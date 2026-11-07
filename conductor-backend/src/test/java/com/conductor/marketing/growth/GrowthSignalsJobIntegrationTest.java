package com.conductor.marketing.growth;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.marketing.growth.GrowthSignalsJob.Summary;
import com.conductor.marketing.growth.ITunesPublicClient.ITunesUnavailableException;
import com.conductor.marketing.growth.ITunesPublicClient.Listing;
import com.conductor.marketing.growth.ITunesPublicClient.Review;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.conductor.marketing.growth.GrowthSignalsFixture.appId;
import static com.conductor.marketing.growth.GrowthSignalsFixture.date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The weekly job against real Postgres with the public iTunes client stubbed: what it writes, that a second run in
 * the same ISO week writes nothing twice, and that one failing app or keyword does not stop the others.
 */
class GrowthSignalsJobIntegrationTest extends AbstractNoneWebIntegrationTest {

    /** A Wednesday; its ISO week starts on Monday 2026-11-02. */
    private static final LocalDate WEDNESDAY = date("2026-11-04");
    private static final LocalDate MONDAY = date("2026-11-02");
    private static final LocalDate NEXT_WEDNESDAY = date("2026-11-11");
    private static final LocalDate NEXT_MONDAY = date("2026-11-09");

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private MarketingCompetitorRepository competitorRepository;
    @Autowired private MarketingTrackedKeywordRepository trackedKeywordRepository;
    @Autowired private MarketingAppSnapshotRepository snapshotRepository;
    @Autowired private MarketingReviewRepository reviewRepository;
    @Autowired private GrowthSignalsSourceRepository sourceRepository;
    @Autowired private TrackedKeywordSource trackedKeywordSource;
    @Autowired private ObjectMapper objectMapper;

    private final ITunesPublicClient client = mock(ITunesPublicClient.class);
    private GrowthSignalsFixture fixture;
    private User owner;
    private Project project;
    private String ours;
    private String rival;

    @BeforeEach
    void setUp() {
        fixture = new GrowthSignalsFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                workItemRepository);
        owner = fixture.user();
        project = fixture.project(owner);
        ours = appId();
        rival = appId();
        fixture.appStoreConnect(project, ours, null);
        addCompetitor(rival, "Rival", "us", true);
        when(client.lookup(ours, "us")).thenReturn(Optional.of(listing(ours, "Rexipe", "0.00", "4.6", 135L, "1.1")));
        when(client.lookup(rival, "us")).thenReturn(Optional.of(listing(rival, "Rival", "0.99", "4.2", 900L, "3.0")));
        when(client.recentReviews(anyString(), anyString())).thenReturn(List.of());
        when(client.searchAppIds(anyString(), anyString())).thenReturn(List.of());
    }

    private GrowthSignalsJob job(boolean enabled) {
        return new GrowthSignalsJob(client, sourceRepository, competitorRepository, trackedKeywordSource,
                snapshotRepository, reviewRepository, objectMapper,
                Clock.fixed(WEDNESDAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC), enabled);
    }

    private void addCompetitor(String appId, String name, String country, boolean active) {
        MarketingCompetitor c = new MarketingCompetitor();
        c.setProjectId(project.getId());
        c.setAppId(appId);
        c.setName(name);
        c.setCountry(country);
        c.setActive(active);
        competitorRepository.save(c);
    }

    private static Listing listing(String appId, String name, String price, String rating, long count, String version) {
        return new Listing(appId, name, new BigDecimal(price), "USD", new BigDecimal(rating), count, version,
                "A subtitle", "hash-" + appId);
    }

    private static Review review(String id, int rating, String at) {
        return new Review(id, rating, "Title " + id, "Body " + id, "reader", OffsetDateTime.parse(at));
    }

    private List<MarketingAppSnapshot> snapshots(String appId) {
        return snapshotRepository.findByProjectIdAndAppIdOrderByCapturedOnDesc(project.getId(), appId,
                PageRequest.of(0, 10));
    }

    private List<MarketingReview> reviews(String appId) {
        return reviewRepository.findRecent(project.getId(), appId, PageRequest.of(0, 50));
    }

    // ---- what a run writes ----------------------------------------------------------------------------------

    @Test
    void aRunWritesThisWeeksSnapshotForOurAppAndEachCompetitorOnTheWeeksMonday() {
        Summary summary = job(true).captureProject(project.getId(), GrowthSignalsJob.weekStart(WEDNESDAY), WEDNESDAY);

        assertThat(summary).isEqualTo(new Summary(1, 2, 0, 0));
        MarketingAppSnapshot mine = snapshots(ours).get(0);
        assertThat(mine.getCapturedOn()).isEqualTo(MONDAY).matches(d -> d.getDayOfWeek() == DayOfWeek.MONDAY);
        assertThat(mine.getTitle()).isEqualTo("Rexipe");
        assertThat(mine.getCountry()).isEqualTo("us");
        assertThat(mine.getPrice()).isEqualByComparingTo("0.00");
        assertThat(mine.getCurrency()).isEqualTo("USD");
        assertThat(mine.getRatingAvg()).isEqualByComparingTo("4.6");
        assertThat(mine.getRatingCount()).isEqualTo(135L);
        assertThat(mine.getVersion()).isEqualTo("1.1");
        assertThat(mine.getSubtitle()).isEqualTo("A subtitle");
        assertThat(mine.getDescriptionHash()).isEqualTo("hash-" + ours);
        assertThat(mine.getKeywordRanks()).isNull();
        assertThat(snapshots(rival)).hasSize(1);
        assertThat(snapshots(rival).get(0).getRatingCount()).isEqualTo(900L);
    }

    @Test
    void aRunWritesTheRecentReviewsOfEveryApp() {
        when(client.recentReviews(ours, "us")).thenReturn(List.of(
                review("a2", 5, "2026-11-02T10:00:00Z"), review("a1", 4, "2026-11-01T10:00:00Z")));
        when(client.recentReviews(rival, "us")).thenReturn(List.of(review("b1", 2, "2026-11-01T09:00:00Z")));

        Summary summary = job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(summary.reviews()).isEqualTo(3);
        assertThat(reviews(ours)).extracting(MarketingReview::getReviewId).containsExactly("a2", "a1");
        assertThat(reviews(ours).get(0).getRating()).isEqualTo(5);
        assertThat(reviews(ours).get(0).getBody()).isEqualTo("Body a2");
        assertThat(reviews(rival)).extracting(MarketingReview::getReviewId).containsExactly("b1");
    }

    @Test
    void aRunRecordsEachAppsPositionInEachTrackedKeywordAndNullWhenItIsNotListed() throws Exception {
        MarketingTrackedKeyword recipe = new MarketingTrackedKeyword();
        recipe.setProjectId(project.getId());
        recipe.setKeyword("recipe app");
        recipe.setCountry("us");
        trackedKeywordRepository.save(recipe);
        MarketingTrackedKeyword planner = new MarketingTrackedKeyword();
        planner.setProjectId(project.getId());
        planner.setKeyword("meal planner");
        planner.setCountry("us");
        trackedKeywordRepository.save(planner);
        when(client.searchAppIds("recipe app", "us")).thenReturn(List.of("111", ours, rival));
        when(client.searchAppIds("meal planner", "us")).thenReturn(List.of("222", rival));

        job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        JsonNode mine = objectMapper.readTree(snapshots(ours).get(0).getKeywordRanks());
        assertThat(mine.get("recipe app").asInt()).isEqualTo(2);
        assertThat(mine.has("meal planner")).isTrue();
        assertThat(mine.get("meal planner").isNull()).isTrue();
        JsonNode theirs = objectMapper.readTree(snapshots(rival).get(0).getKeywordRanks());
        assertThat(theirs.get("recipe app").asInt()).isEqualTo(3);
        assertThat(theirs.get("meal planner").asInt()).isEqualTo(2);
    }

    @Test
    void theCountryOfOurConnectionIsUsedForOurLookupAndReviews() {
        Project other = fixture.project(owner);
        String app = appId();
        fixture.appStoreConnect(other, app, "gb");
        when(client.lookup(app, "gb")).thenReturn(Optional.of(listing(app, "Rexipe UK", "0.00", "4.0", 10L, "1.0")));

        job(true).captureProject(other.getId(), MONDAY, WEDNESDAY);

        verify(client).lookup(app, "gb");
        verify(client).recentReviews(app, "gb");
        assertThat(snapshotRepository.findByProjectIdAndAppIdOrderByCapturedOnDesc(other.getId(), app,
                PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    void aCompetitorIsLookedUpInItsOwnCountry() {
        String gb = appId();
        addCompetitor(gb, "UK Rival", "gb", true);
        when(client.lookup(gb, "gb")).thenReturn(Optional.of(listing(gb, "UK Rival", "1.29", "4.0", 50L, "2.0")));

        job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        verify(client).lookup(gb, "gb");
        assertThat(snapshots(gb)).hasSize(1);
    }

    // ---- idempotent per (app, week) -------------------------------------------------------------------------

    @Test
    void runningTwiceInOneWeekWritesNoDuplicateSnapshotOrReview() {
        when(client.recentReviews(ours, "us")).thenReturn(List.of(review("a1", 5, "2026-11-02T10:00:00Z")));
        GrowthSignalsJob job = job(true);

        Summary first = job.captureProject(project.getId(), MONDAY, WEDNESDAY);
        Summary second = job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(first.snapshots()).isEqualTo(2);
        assertThat(first.reviews()).isEqualTo(1);
        assertThat(second).isEqualTo(new Summary(1, 0, 0, 0));
        assertThat(snapshots(ours)).hasSize(1);
        assertThat(snapshots(rival)).hasSize(1);
        assertThat(reviews(ours)).hasSize(1);
        // the second run did not even look the apps up again
        verify(client).lookup(ours, "us");
        verify(client).lookup(rival, "us");
    }

    private void track(String keyword) {
        MarketingTrackedKeyword k = new MarketingTrackedKeyword();
        k.setProjectId(project.getId());
        k.setKeyword(keyword);
        k.setCountry("us");
        trackedKeywordRepository.save(k);
    }

    @Test
    void aRerunInTheSameWeekAfterEverySnapshotExistsSearchesNoKeywordAtAll() {
        track("recipe app");
        track("meal planner");
        GrowthSignalsJob job = job(true);
        job.captureProject(project.getId(), MONDAY, WEDNESDAY);
        verify(client).searchAppIds("recipe app", "us");
        verify(client).searchAppIds("meal planner", "us");
        clearInvocations(client);

        Summary second = job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(second).isEqualTo(new Summary(1, 0, 0, 0));
        verify(client, never()).searchAppIds(anyString(), anyString());
        verify(client, never()).lookup(anyString(), anyString());
    }

    @Test
    void aPartialRerunSearchesTheKeywordsOnceForTheAppsThatStillNeedASnapshot() throws Exception {
        track("recipe app");
        track("meal planner");
        when(client.searchAppIds("recipe app", "us")).thenReturn(List.of(ours, rival));
        when(client.searchAppIds("meal planner", "us")).thenReturn(List.of(rival, ours));
        when(client.lookup(rival, "us")).thenThrow(new ITunesUnavailableException("boom", null));
        GrowthSignalsJob job = job(true);
        job.captureProject(project.getId(), MONDAY, WEDNESDAY);
        assertThat(snapshots(rival)).isEmpty();
        clearInvocations(client);
        doReturn(Optional.of(listing(rival, "Rival", "0.99", "4.2", 900L, "3.0"))).when(client).lookup(rival, "us");
        when(client.recentReviews(anyString(), anyString())).thenReturn(List.of());

        Summary retry = job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(retry).isEqualTo(new Summary(1, 1, 0, 0));
        // one search per keyword, shared by the one app that needs it, and our finished app is not looked up again
        verify(client, times(1)).searchAppIds("recipe app", "us");
        verify(client, times(1)).searchAppIds("meal planner", "us");
        verify(client, never()).lookup(ours, "us");
        JsonNode theirs = objectMapper.readTree(snapshots(rival).get(0).getKeywordRanks());
        assertThat(theirs.get("recipe app").asInt()).isEqualTo(2);
        assertThat(theirs.get("meal planner").asInt()).isEqualTo(1);
        assertThat(snapshots(ours)).hasSize(1);
    }

    @Test
    void whenNoLookupFindsAListingNoKeywordIsSearched() {
        track("recipe app");
        when(client.lookup(ours, "us")).thenReturn(Optional.empty());
        when(client.lookup(rival, "us")).thenThrow(new ITunesUnavailableException("boom", null));

        job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        verify(client, never()).searchAppIds(anyString(), anyString());
    }

    @Test
    void aLaterDayInTheSameWeekIsTheSameSnapshot() {
        GrowthSignalsJob job = job(true);
        job.captureProject(project.getId(), GrowthSignalsJob.weekStart(date("2026-11-02")), date("2026-11-02"));
        job.captureProject(project.getId(), GrowthSignalsJob.weekStart(date("2026-11-08")), date("2026-11-08"));

        assertThat(snapshots(ours)).hasSize(1);
        assertThat(snapshots(ours).get(0).getCapturedOn()).isEqualTo(MONDAY);
    }

    @Test
    void aNewWeekAddsASecondSnapshotAndOnlyTheNewReviews() {
        when(client.recentReviews(ours, "us")).thenReturn(List.of(review("a1", 5, "2026-11-02T10:00:00Z")));
        GrowthSignalsJob job = job(true);
        job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        when(client.recentReviews(ours, "us")).thenReturn(List.of(
                review("a2", 3, "2026-11-09T10:00:00Z"), review("a1", 5, "2026-11-02T10:00:00Z")));
        when(client.lookup(ours, "us")).thenReturn(Optional.of(listing(ours, "Rexipe", "0.00", "4.6", 150L, "1.2")));
        Summary second = job.captureProject(project.getId(), NEXT_MONDAY, NEXT_WEDNESDAY);

        assertThat(second.snapshots()).isEqualTo(2);
        assertThat(second.reviews()).isEqualTo(1);
        assertThat(snapshots(ours)).extracting(MarketingAppSnapshot::getCapturedOn).containsExactly(NEXT_MONDAY, MONDAY);
        assertThat(snapshots(ours).get(0).getRatingCount()).isEqualTo(150L);
        assertThat(reviews(ours)).extracting(MarketingReview::getReviewId).containsExactly("a2", "a1");
    }

    // ---- failure isolation ----------------------------------------------------------------------------------

    @Test
    void aFailingAppDoesNotStopTheOthersAndIsFilledByTheNextRun() {
        String second = appId();
        addCompetitor(second, "Second Rival", "us", true);
        when(client.lookup(second, "us")).thenReturn(Optional.of(listing(second, "Second Rival", "2.99", "3.9", 40L, "1.0")));
        when(client.lookup(rival, "us")).thenThrow(new ITunesUnavailableException("boom", null));
        GrowthSignalsJob job = job(true);

        Summary first = job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(first.failures()).isEqualTo(1);
        assertThat(first.snapshots()).isEqualTo(2);
        assertThat(snapshots(ours)).hasSize(1);
        assertThat(snapshots(second)).hasSize(1);
        assertThat(snapshots(rival)).isEmpty();

        doReturn(Optional.of(listing(rival, "Rival", "0.99", "4.2", 900L, "3.0"))).when(client).lookup(rival, "us");
        Summary retry = job.captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(retry).isEqualTo(new Summary(1, 1, 0, 0));
        assertThat(snapshots(rival)).hasSize(1);
        assertThat(snapshots(ours)).hasSize(1);
    }

    @Test
    void aFailingReviewFetchStillKeepsTheSnapshot() {
        when(client.recentReviews(ours, "us")).thenThrow(new ITunesUnavailableException("rss down", null));

        Summary summary = job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(summary.failures()).isEqualTo(1);
        assertThat(snapshots(ours)).hasSize(1);
        assertThat(snapshots(rival)).hasSize(1);
    }

    @Test
    void aFailingKeywordSearchLeavesThatKeywordOutOfTheRanksInsteadOfRecordingNotFound() throws Exception {
        MarketingTrackedKeyword good = new MarketingTrackedKeyword();
        good.setProjectId(project.getId());
        good.setKeyword("good keyword");
        good.setCountry("us");
        trackedKeywordRepository.save(good);
        MarketingTrackedKeyword bad = new MarketingTrackedKeyword();
        bad.setProjectId(project.getId());
        bad.setKeyword("bad keyword");
        bad.setCountry("us");
        trackedKeywordRepository.save(bad);
        when(client.searchAppIds("good keyword", "us")).thenReturn(List.of(ours));
        when(client.searchAppIds("bad keyword", "us")).thenThrow(new ITunesUnavailableException("search down", null));

        Summary summary = job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(summary.failures()).isEqualTo(1);
        JsonNode ranks = objectMapper.readTree(snapshots(ours).get(0).getKeywordRanks());
        assertThat(ranks.get("good keyword").asInt()).isEqualTo(1);
        assertThat(ranks.has("bad keyword")).isFalse();
    }

    @Test
    void anAppTheStorefrontDoesNotCarryGetsNoSnapshotAndNoReviewFetch() {
        when(client.lookup(rival, "us")).thenReturn(Optional.empty());

        Summary summary = job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        assertThat(summary.snapshots()).isEqualTo(1);
        assertThat(summary.failures()).isEqualTo(0);
        assertThat(snapshots(rival)).isEmpty();
        verify(client, never()).recentReviews(rival, "us");
    }

    // ---- which projects and apps ----------------------------------------------------------------------------

    @Test
    void aRemovedCompetitorIsNotCaptured() {
        MarketingCompetitor row = competitorRepository.findByProjectIdAndAppId(project.getId(), rival).orElseThrow();
        row.setActive(false);
        competitorRepository.save(row);

        job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        verify(client, never()).lookup(rival, "us");
        assertThat(snapshots(rival)).isEmpty();
        assertThat(snapshots(ours)).hasSize(1);
    }

    @Test
    void aProjectWithOnlyCompetitorsIsCapturedAndOneWithNeitherIsNot() {
        Project onlyRivals = fixture.project(owner);
        String solo = appId();
        MarketingCompetitor c = new MarketingCompetitor();
        c.setProjectId(onlyRivals.getId());
        c.setAppId(solo);
        c.setName("Solo");
        competitorRepository.save(c);
        when(client.lookup(solo, "us")).thenReturn(Optional.of(listing(solo, "Solo", "1.00", "4.0", 5L, "1.0")));
        Project neither = fixture.project(owner);

        job(true).capture(WEDNESDAY);

        assertThat(snapshotRepository.findByProjectIdAndAppIdOrderByCapturedOnDesc(onlyRivals.getId(), solo,
                PageRequest.of(0, 5))).hasSize(1);
        assertThat(snapshotRepository.findByProjectIdAndAppIdOrderByCapturedOnDesc(project.getId(), ours,
                PageRequest.of(0, 5))).hasSize(1);
        assertThat(sourceRepository.findProjectIdsWithAppStoreConnect()).doesNotContain(neither.getId());
        assertThat(competitorRepository.findProjectIdsWithActiveCompetitors()).doesNotContain(neither.getId());
    }

    @Test
    void ourAppCountedAsACompetitorIsNotLookedUpTwice() {
        addCompetitor(appId(), "Other", "us", false);
        MarketingCompetitor dup = new MarketingCompetitor();
        dup.setProjectId(project.getId());
        dup.setAppId(ours);
        dup.setName("Me again");
        competitorRepository.save(dup);

        job(true).captureProject(project.getId(), MONDAY, WEDNESDAY);

        verify(client).lookup(ours, "us");
        assertThat(snapshots(ours)).hasSize(1);
    }

    @Test
    void aConnectionWithoutAUsableAppIdContributesNothing() {
        Project p = fixture.project(owner);
        fixture.connect(p, "app-store-connect", "{\"appId\":\"not-digits\"}");
        fixture.connect(p, "app-store-connect", "{}");

        Summary summary = job(true).captureProject(p.getId(), MONDAY, WEDNESDAY);

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 0));
        verify(client, never()).lookup(eq("not-digits"), anyString());
    }

    @Test
    void aDisabledJobDoesNothing() {
        job(false).run();

        verifyNoInteractions(client);
        assertThat(snapshots(ours)).isEmpty();
    }

    @Test
    void theWeekStartIsTheMondayOfTheIsoWeek() {
        assertThat(GrowthSignalsJob.weekStart(date("2026-11-02"))).isEqualTo(date("2026-11-02"));
        assertThat(GrowthSignalsJob.weekStart(date("2026-11-08"))).isEqualTo(date("2026-11-02"));
        assertThat(GrowthSignalsJob.weekStart(date("2026-11-09"))).isEqualTo(date("2026-11-09"));
        assertThat(GrowthSignalsJob.weekStart(date("2027-01-01"))).isEqualTo(date("2026-12-28"));
    }
}
