package com.conductor.marketing.creators;

import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.creators.CreatorResultsService.Results;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link CreatorResultsService} against real Postgres: a creator's posts with their views, the settled
 * downloads of its campaign links and the settled redemptions of its offer code over a window, and that an
 * unknown figure reads null with a note rather than 0. "Today" is a fixed day so every window is deterministic.
 */
@Transactional
class CreatorResultsServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final String NO_OFFER_FEED = "Your product backend does not send offer_redemptions yet";

    @Autowired private CreatorResultsService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private EntityManager entityManager;

    private CreatorResultsFixture fixture;
    private User user;
    private Project project;
    private String storeConnection;
    private String productConnection;

    @BeforeEach
    void setUp() {
        fixture = new CreatorResultsFixture(userRepository, projectRepository, memberRepository, connectionRepository,
                workItemRepository, targetRepository, metricRepository, dailyRepository, entityManager);
        user = fixture.user();
        project = fixture.project(user);
        fixture.member(project, user, MemberRole.ADMIN);
        storeConnection = fixture.connection(project, "app-store-connect");
        productConnection = fixture.connection(project, "product-metrics");
    }

    private Results results(WorkItem creator) {
        return service.results(project.getId(), creator.getId(), FROM, TO, TODAY);
    }

    private void downloads(String token, String day, double value) {
        fixture.dailyRow(project, storeConnection, "app-store-connect", "STORE", "campaign_downloads", "ct:" + token,
                LocalDate.parse(day), value, false);
    }

    private void redemptions(String code, String day, double value) {
        fixture.dailyRow(project, productConnection, "product-metrics", "PRODUCT", "offer_redemptions", "offer:" + code,
                LocalDate.parse(day), value, false);
    }

    // ---- the whole picture ---------------------------------------------------------------------------

    @Test
    void aCreatorShowsItsPostsLinkFamilyDownloadsAndOfferCodeRedemptions() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "@Jane", "offerCode", "JANE10"));

        WorkItem child = fixture.post(project, user, "Jane unboxing", creator.getId());
        PostPublishTarget tiktok = fixture.destination(child, "tiktok", PostPublishTargetState.PUBLISHED);
        PostPublishTarget instagram = fixture.destination(child, "instagram", PostPublishTargetState.PUBLISHED);
        fixture.snapshot(tiktok, project, OffsetDateTime.now().minusDays(2), 80L, false);
        fixture.snapshot(tiktok, project, OffsetDateTime.now().minusDays(1), 120L, false);
        fixture.snapshot(instagram, project, OffsetDateTime.now().minusDays(1), 30L, false);
        WorkItem tagged = fixture.tagged(fixture.post(project, user, "Jane story", null), "creator:jane");
        fixture.snapshot(fixture.destination(tagged, "instagram", PostPublishTargetState.PUBLISHED), project,
                OffsetDateTime.now().minusDays(1), 10L, false);
        WorkItem someoneElses = fixture.tagged(fixture.post(project, user, "Joe story", null), "creator:joe");
        fixture.snapshot(fixture.destination(someoneElses, "instagram", PostPublishTargetState.PUBLISHED), project,
                OffsetDateTime.now().minusDays(1), 999L, false);
        // a TASK child of the creator is not a Post
        WorkItem task = fixture.item(project, user, "TASK", "TASK", "TODO", "Chase Jane");
        task.setParentWorkItemId(creator.getId());
        workItemRepository.saveAndFlush(task);

        fixture.trackedLink(project, user, "CREATOR", "jane-a", creator.getId());
        fixture.trackedLink(project, user, "CREATOR", "jane-b", creator.getId());
        fixture.trackedLink(project, user, "BIO", "jane-bio", creator.getId());
        fixture.trackedLink(project, user, "CREATOR", "joe-a", fixture.creator(project, user, "POSTED", Map.of()).getId());
        downloads("jane-a", "2026-10-01", 3);
        downloads("jane-a", "2026-10-02", 4);
        downloads("jane-b", "2026-10-10", 6);
        downloads("jane-bio", "2026-10-10", 50);
        downloads("joe-a", "2026-10-10", 70);
        downloads("jane-a", "2026-09-28", 100);
        downloads("jane-a", "2026-10-27", 100);
        fixture.dailyRow(project, storeConnection, "app-store-connect", "STORE", "campaign_downloads", "ct:jane-a",
                LocalDate.parse("2026-10-11"), 1000, true);

        redemptions("JANE10", "2026-10-01", 2);
        redemptions("JANE10", "2026-10-05", 3);
        redemptions("jane10", "2026-10-06", 1);
        redemptions("JOE10", "2026-10-05", 40);
        redemptions("JANE10", "2026-10-28", 90);

        Results out = results(creator);

        assertThat(out.window().from()).isEqualTo(FROM);
        assertThat(out.window().to()).isEqualTo(TO);
        assertThat(out.creator().workItemId()).isEqualTo(creator.getId());
        assertThat(out.creator().displayId()).isEqualTo(project.getKey() + "-" + creator.getSequenceNumber());
        assertThat(out.creator().handle()).isEqualTo("@Jane");
        assertThat(out.creator().status()).isEqualTo("POSTED");

        assertThat(out.postCount()).isEqualTo(2);
        assertThat(out.posts()).extracting(CreatorResultsService.Post::title)
                .containsExactlyInAnyOrder("Jane unboxing", "Jane story");
        assertThat(out.posts()).filteredOn(p -> p.title().equals("Jane unboxing"))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.workItemId()).isEqualTo(child.getId());
                    assertThat(p.displayId()).isEqualTo(project.getKey() + "-" + child.getSequenceNumber());
                    assertThat(p.status()).isEqualTo("PUBLISHED");
                    assertThat(p.views()).isEqualTo(150L);
                });
        assertThat(out.posts()).filteredOn(p -> p.title().equals("Jane story"))
                .singleElement().satisfies(p -> assertThat(p.views()).isEqualTo(10L));

        assertThat(out.linkDownloads().tokens()).containsExactly("jane-a", "jane-b");
        assertThat(out.linkDownloads().downloads()).isEqualTo(13.0);
        assertThat(out.linkDownloads().belowThreshold()).isFalse();
        assertThat(out.linkDownloads().note()).isNull();

        assertThat(out.offerRedemptions().code()).isEqualTo("JANE10");
        assertThat(out.offerRedemptions().value()).isEqualTo(6.0);
        assertThat(out.offerRedemptions().note()).isNull();
    }

    // ---- redemptions: unknown is null with a note, never 0 --------------------------------------------

    @Test
    void withNoOfferRowsAtAllRedemptionsAreNullWithTheBackendNote() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane", "offerCode", "JANE10"));

        Results out = results(creator);

        assertThat(out.offerRedemptions().code()).isEqualTo("JANE10");
        assertThat(out.offerRedemptions().value()).isNull();
        assertThat(out.offerRedemptions().note()).isEqualTo(NO_OFFER_FEED);
    }

    @Test
    void offerRowsForOtherCodesOnlyLeaveThisCodeNullWithAnUnreportedNote() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane", "offerCode", "JANE10"));
        redemptions("JOE10", "2026-10-05", 40);

        Results out = results(creator);

        assertThat(out.offerRedemptions().value()).isNull();
        assertThat(out.offerRedemptions().note()).contains("JANE10").contains("not the same as 0")
                .isNotEqualTo(NO_OFFER_FEED);
    }

    @Test
    void aReportedZeroIsZeroNotNull() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane", "offerCode", "JANE10"));
        redemptions("JANE10", "2026-10-05", 0);

        Results out = results(creator);

        assertThat(out.offerRedemptions().value()).isEqualTo(0.0);
        assertThat(out.offerRedemptions().note()).isNull();
    }

    @Test
    void aCreatorWithoutAnOfferCodeAsksForOneInsteadOfShowingZero() {
        WorkItem creator = fixture.creator(project, user, "PROSPECT", Map.of());

        Results out = results(creator);

        assertThat(out.creator().handle()).isNull();
        assertThat(out.offerRedemptions().code()).isNull();
        assertThat(out.offerRedemptions().value()).isNull();
        assertThat(out.offerRedemptions().note()).contains("offer code");
    }

    @Test
    void aProvisionalRedemptionDayIsNotCounted() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("offerCode", "JANE10"));
        fixture.dailyRow(project, productConnection, "product-metrics", "PRODUCT", "offer_redemptions", "offer:JANE10",
                LocalDate.parse("2026-10-05"), 9, true);

        Results out = results(creator);

        assertThat(out.offerRedemptions().value()).isNull();
    }

    // ---- link downloads: unknown is null (below threshold), never 0 ------------------------------------

    @Test
    void tokensWithNoCampaignRowsAreNullAndBelowAppleReportingThreshold() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));
        fixture.trackedLink(project, user, "CREATOR", "jane-a", creator.getId());
        fixture.trackedLink(project, user, "CREATOR", "jane-b", creator.getId());

        Results out = results(creator);

        assertThat(out.linkDownloads().tokens()).containsExactly("jane-a", "jane-b");
        assertThat(out.linkDownloads().downloads()).isNull();
        assertThat(out.linkDownloads().belowThreshold()).isTrue();
        assertThat(out.linkDownloads().note()).contains("reporting threshold");
    }

    @Test
    void aCreatorWithNoLinkAtAllIsNullButNotBelowTheThreshold() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));

        Results out = results(creator);

        assertThat(out.linkDownloads().tokens()).isEmpty();
        assertThat(out.linkDownloads().downloads()).isNull();
        assertThat(out.linkDownloads().belowThreshold()).isFalse();
        assertThat(out.linkDownloads().note()).contains("campaign link");
    }

    @Test
    void whenOnlyOneTokenIsReportedTheTotalCountsItAndSaysItIsALowerBound() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));
        fixture.trackedLink(project, user, "CREATOR", "jane-a", creator.getId());
        fixture.trackedLink(project, user, "CREATOR", "jane-b", creator.getId());
        downloads("jane-a", "2026-10-01", 7);

        Results out = results(creator);

        assertThat(out.linkDownloads().downloads()).isEqualTo(7.0);
        assertThat(out.linkDownloads().belowThreshold()).isFalse();
        assertThat(out.linkDownloads().note()).contains("1 of 2").contains("lower bound");
    }

    @Test
    void theLinkTokenFieldIsReadAndMergedWithTheTrackedLinksWithoutDoubleCounting() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("linkToken", " Jane-A "));
        fixture.trackedLink(project, user, "CREATOR", "jane-a", creator.getId());
        fixture.trackedLink(project, user, "CREATOR", "jane-c", creator.getId());
        downloads("jane-a", "2026-10-01", 5);
        downloads("jane-c", "2026-10-02", 2);

        Results out = results(creator);

        assertThat(out.linkDownloads().tokens()).containsExactly("jane-a", "jane-c");
        assertThat(out.linkDownloads().downloads()).isEqualTo(7.0);
    }

    @Test
    void withoutATrackedLinkTableTheLinkTokenFieldStillWorks() {
        fixture.dropTrackedLinkTable();
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("linkToken", "jane-a"));
        downloads("jane-a", "2026-10-01", 5);

        Results out = results(creator);

        assertThat(out.linkDownloads().tokens()).containsExactly("jane-a");
        assertThat(out.linkDownloads().downloads()).isEqualTo(5.0);
    }

    @Test
    void withoutATrackedLinkTableAndNoTokenDownloadsAreNullWithANote() {
        fixture.dropTrackedLinkTable();
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));

        Results out = results(creator);

        assertThat(out.linkDownloads().tokens()).isEmpty();
        assertThat(out.linkDownloads().downloads()).isNull();
        assertThat(out.linkDownloads().belowThreshold()).isFalse();
        assertThat(out.linkDownloads().note()).isNotBlank();
    }

    // ---- posts ----------------------------------------------------------------------------------------

    @Test
    void aPostThatHasNotReportedViewsHasNullViewsNotZero() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));
        WorkItem draft = fixture.post(project, user, "Draft, no destination yet", creator.getId());
        WorkItem unreported = fixture.post(project, user, "Published, not read yet", creator.getId());
        fixture.destination(unreported, "tiktok", PostPublishTargetState.PUBLISHED);
        WorkItem unavailable = fixture.post(project, user, "Metrics unavailable", creator.getId());
        PostPublishTarget target = fixture.destination(unavailable, "tiktok", PostPublishTargetState.PUBLISHED);
        fixture.snapshot(target, project, OffsetDateTime.now().minusDays(1), 5L, true);
        WorkItem pending = fixture.post(project, user, "Pending destination", creator.getId());
        fixture.snapshot(fixture.destination(pending, "tiktok", PostPublishTargetState.PENDING), project,
                OffsetDateTime.now().minusDays(1), 77L, false);

        Results out = results(creator);

        assertThat(out.postCount()).isEqualTo(4);
        assertThat(out.posts()).isNotEmpty().allSatisfy(p -> assertThat(p.views()).isNull());
        assertThat(out.posts()).extracting(CreatorResultsService.Post::workItemId)
                .contains(draft.getId(), unreported.getId(), unavailable.getId(), pending.getId());
    }

    @Test
    void aTaggedPostInAnotherProjectIsNotListed() {
        User other = fixture.user();
        Project otherProject = fixture.project(other);
        fixture.member(otherProject, other, MemberRole.ADMIN);
        fixture.tagged(fixture.post(otherProject, other, "Other workspace jane post", null), "creator:jane");
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));

        assertThat(results(creator).posts()).isEmpty();
    }

    @Test
    void aCreatorWithNoPostsHasAnEmptyListAndNoHandleMatchIsAttempted() {
        WorkItem creator = fixture.creator(project, user, "PROSPECT", Map.of());
        fixture.tagged(fixture.post(project, user, "Untagged-handle post", null), "creator:");

        Results out = results(creator);

        assertThat(out.posts()).isEmpty();
        assertThat(out.postCount()).isZero();
    }

    @Test
    void onlyTheNewestFiftyPostsAreListedButAllAreCounted() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));
        for (int i = 0; i < 53; i++) {
            fixture.post(project, user, "Post " + i, creator.getId());
        }

        Results out = results(creator);

        assertThat(out.postCount()).isEqualTo(53);
        assertThat(out.posts()).hasSize(50);
        assertThat(out.posts().get(0).title()).isEqualTo("Post 52");
    }

    // ---- which Work Items are creators ------------------------------------------------------------------

    @Test
    void aWorkItemThatIsNotOnTheCreatorsWorkflowIsNotFound() {
        WorkItem issue = fixture.item(project, user, "BUG", "ENGINEERING", "OPEN", "Crash on launch");

        assertThatThrownBy(() -> service.results(project.getId(), issue.getId(), FROM, TO, TODAY))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aCreatorOfAnotherProjectIsNotFound() {
        User other = fixture.user();
        Project otherProject = fixture.project(other);
        WorkItem theirs = fixture.creator(otherProject, other, "POSTED", Map.of("handle", "jane"));

        assertThatThrownBy(() -> service.results(project.getId(), theirs.getId(), FROM, TO, TODAY))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void anUnknownReferenceIsNotFound() {
        assertThatThrownBy(() -> service.results(project.getId(), "no-such-item", FROM, TO, TODAY))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.results(project.getId(), project.getKey() + "-9999", FROM, TO, TODAY))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aCreatorIsFoundByItsDisplayIdButOnlyUnderItsOwnProjectKey() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("handle", "jane"));
        String displayId = project.getKey() + "-" + creator.getSequenceNumber();

        assertThat(service.results(project.getId(), displayId, FROM, TO, TODAY).creator().workItemId())
                .isEqualTo(creator.getId());
        assertThat(service.results(project.getId(), displayId.toLowerCase(), FROM, TO, TODAY).creator().workItemId())
                .isEqualTo(creator.getId());
        assertThatThrownBy(() -> service.results(project.getId(), "ZZZ-" + creator.getSequenceNumber(), FROM, TO, TODAY))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- the window --------------------------------------------------------------------------------------

    @Test
    void theDefaultWindowIsTheTwentyEightDaysEndingYesterday() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of("linkToken", "jane-a"));

        Results out = service.results(project.getId(), creator.getId(), null, null, TODAY);

        assertThat(out.window().to()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(out.window().from()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void aWindowWithOnlyOneEndFillsTheOtherEnd() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of());

        Results onlyTo = service.results(project.getId(), creator.getId(), null, TO, TODAY);
        Results onlyFrom = service.results(project.getId(), creator.getId(), FROM, null, TODAY);

        assertThat(onlyTo.window().from()).isEqualTo(TO.minusDays(27));
        assertThat(onlyTo.window().to()).isEqualTo(TO);
        assertThat(onlyFrom.window().from()).isEqualTo(FROM);
        assertThat(onlyFrom.window().to()).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    void aReversedWindowIsRefused() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of());

        assertThatThrownBy(() -> service.results(project.getId(), creator.getId(), TO, FROM, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("from");
    }

    @Test
    void aWindowLongerThanNinetyTwoDaysIsRefused() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of());

        assertThatThrownBy(() -> service.results(project.getId(), creator.getId(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 26), TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("92");
        assertThat(service.results(project.getId(), creator.getId(),
                LocalDate.of(2026, 7, 26), LocalDate.of(2026, 10, 25), TODAY).window().from())
                .isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void aWindowThatEndsTodayOrLaterIsRefused() {
        WorkItem creator = fixture.creator(project, user, "POSTED", Map.of());

        assertThatThrownBy(() -> service.results(project.getId(), creator.getId(), FROM, TODAY, TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("yesterday");
    }
}
