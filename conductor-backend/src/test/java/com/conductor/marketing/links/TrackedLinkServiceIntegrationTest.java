package com.conductor.marketing.links;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.marketing.links.TrackedLinkService.FamilyPerformance;
import com.conductor.marketing.links.TrackedLinkService.LinkInput;
import com.conductor.marketing.links.TrackedLinkService.Performance;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link TrackedLinkService} against real Postgres: explicit creation and its refusals, the one-link-per-Post
 * rule, and family performance read from {@code channel_metric_daily} ({@code STORE campaign_downloads}, dimension
 * {@code ct:{token}}) and the Posts' latest destination snapshots. The point of the performance tests is the
 * invariant that a family Apple reported nothing for never reads 0.
 */
@Transactional
class TrackedLinkServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final String APP_ID = "1234567890";
    private static final String PROVIDER_TOKEN = "117962345";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 28);

    @Autowired private TrackedLinkService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;

    private User user;
    private Project project;
    private String projectId;
    private String storeConnectionId;
    private int nextSequence = 1;

    @BeforeEach
    void setUp() {
        user = newUser();
        project = newProject();
        projectId = project.getId();
        storeConnectionId = connect(projectId, "app-store-connect", "ACTIVE",
                "{\"appId\":\"" + APP_ID + "\",\"providerToken\":\"" + PROVIDER_TOKEN + "\"}");
    }

    // ---- explicit creation --------------------------------------------------------------------------

    @Test
    void createBuildsTheAppStoreCampaignLinkFromTheConnectionsAppIdAndProviderToken() {
        TrackedLink link = service.create(projectId,
                new LinkInput("BIO", "social-bios", "ig-bio", "Instagram bio", null), user.getId());

        assertThat(link.getUrl())
                .isEqualTo("https://apps.apple.com/app/apple-store/id1234567890?pt=117962345&ct=ig-bio");
        assertThat(link.getKind()).isEqualTo("BIO");
        assertThat(link.getFamily()).isEqualTo("social-bios");
        assertThat(link.getToken()).isEqualTo("ig-bio");
        assertThat(link.getLabel()).isEqualTo("Instagram bio");
        assertThat(link.getWorkItemId()).isNull();
        assertThat(link.getCreatedBy()).isEqualTo(user.getId());
        assertThat(link.getCreatedAt()).isNotNull();
        assertThat(service.list(projectId, null, null, 10).links()).extracting(TrackedLink::getToken)
                .containsExactly("ig-bio");
    }

    @Test
    void createRefusesATokenThatIsNotLowercaseLettersDigitsAndDashesWithinFortyCharacters() {
        for (String bad : new String[] {"", "  ", "Bio", "-bio", "bio_1", "bio 1", "a".repeat(41)}) {
            assertThatThrownBy(() -> service.create(projectId, new LinkInput("BIO", "bios", bad, null, null), null))
                    .as("token '%s'", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("token");
        }
        assertThat(service.create(projectId, new LinkInput("BIO", "bios", "a".repeat(40), null, null), null)
                .getToken()).hasSize(40);
    }

    @Test
    void createRefusesAFamilyThatIsNotLowercaseLettersDigitsAndDashesWithinSixtyFourCharacters() {
        for (String bad : new String[] {"", "Bios", "bios!", "_bios", "a".repeat(65)}) {
            assertThatThrownBy(() -> service.create(projectId, new LinkInput("BIO", bad, "ok-token", null, null), null))
                    .as("family '%s'", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("family");
        }
    }

    @Test
    void createRefusesAnUnknownKindAndALabelOverTwoHundredCharacters() {
        assertThatThrownBy(() -> service.create(projectId, new LinkInput("BILLBOARD", "bios", "t1", null, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> service.create(projectId, new LinkInput(null, "bios", "t1", null, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> service.create(projectId,
                new LinkInput("BIO", "bios", "t1", "x".repeat(201), null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("label");
    }

    @Test
    void createRefusesATokenThatIsAlreadyTakenInTheProjectButNotOneTakenInAnotherProject() {
        service.create(projectId, new LinkInput("BIO", "bios", "taken", null, null), null);

        assertThatThrownBy(() -> service.create(projectId, new LinkInput("EMAIL", "emails", "taken", null, null), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("taken");

        String other = newProject().getId();
        connect(other, "app-store-connect", "ACTIVE", "{\"appId\":\"42\",\"providerToken\":\"7\"}");
        assertThat(service.create(other, new LinkInput("BIO", "bios", "taken", null, null), null).getUrl())
                .isEqualTo("https://apps.apple.com/app/apple-store/id42?pt=7&ct=taken");
    }

    @Test
    void createWithoutAnAppStoreConnectConnectionIsAConflictWithAPlainMessage() {
        String bare = newProject().getId();

        assertThatThrownBy(() -> service.create(bare, new LinkInput("BIO", "bios", "t1", null, null), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Connect App Store Connect");
        assertThat(service.list(bare, null, null, 10).links()).isEmpty();
    }

    @Test
    void createWithoutAProviderTokenIsAConflictThatSaysWhereToFindIt() {
        for (String config : new String[] {"{\"appId\":\"42\"}", "{\"appId\":\"42\",\"providerToken\":\"  \"}"}) {
            String p = newProject().getId();
            connect(p, "app-store-connect", "ACTIVE", config);

            assertThatThrownBy(() -> service.create(p, new LinkInput("BIO", "bios", "t1", null, null), null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("provider token")
                    .hasMessageContaining("Generate a Link");
        }
    }

    @Test
    void anInactiveAppStoreConnectConnectionIsNotUsed() {
        String p = newProject().getId();
        connect(p, "app-store-connect", "DISCONNECTED", "{\"appId\":\"42\",\"providerToken\":\"7\"}");

        assertThatThrownBy(() -> service.create(p, new LinkInput("BIO", "bios", "t1", null, null), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aPostLinkNeedsAPostAndAPostHasAtMostOneLink() {
        WorkItem post = newPost(project);

        assertThatThrownBy(() -> service.create(projectId, new LinkInput("POST", "posts", "p-1", null, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("workItemId");

        TrackedLink link = service.create(projectId, new LinkInput("POST", "posts", "p-1", "Launch", post.getId()), null);
        assertThat(link.getWorkItemId()).isEqualTo(post.getId());

        assertThatThrownBy(() -> service.create(projectId,
                new LinkInput("POST", "posts", "p-2", null, post.getId()), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already has");
        assertThat(service.list(projectId, "POST", post.getId(), 10).links()).hasSize(1);
    }

    @Test
    void aWorkItemOfAnotherProjectIsNotFound() {
        Project other = newProject();
        WorkItem foreign = newPost(other);

        assertThatThrownBy(() -> service.create(projectId,
                new LinkInput("CREATOR", "creators", "c-1", null, foreign.getId()), null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.create(projectId,
                new LinkInput("CREATOR", "creators", "c-1", null, "no-such-id"), null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aFamilyHoldsOneKindOfLinkAndThePostsFamilyIsReservedForPostLinks() {
        service.create(projectId, new LinkInput("BIO", "social", "ig-bio", null, null), null);

        assertThatThrownBy(() -> service.create(projectId, new LinkInput("EMAIL", "social", "mail-1", null, null), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("social");
        assertThat(service.create(projectId, new LinkInput("BIO", "social", "tt-bio", null, null), null).getKind())
                .isEqualTo("BIO");

        assertThatThrownBy(() -> service.create(projectId, new LinkInput("BIO", "posts", "x-1", null, null), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posts");
    }

    @Test
    void aCreatorLinkMayPointAtItsWorkItem() {
        WorkItem creator = newPost(project);

        TrackedLink link = service.create(projectId,
                new LinkInput("CREATOR", "creators", "cr-ana", "Ana", creator.getId()), null);

        assertThat(link.getWorkItemId()).isEqualTo(creator.getId());
        assertThat(service.list(projectId, null, creator.getId(), 10).links()).extracting(TrackedLink::getToken)
                .containsExactly("cr-ana");
    }

    // ---- listing ------------------------------------------------------------------------------------

    @Test
    void listFiltersByKindAndReportsTheTotalBeyondTheLimit() {
        service.create(projectId, new LinkInput("BIO", "social", "b1", null, null), null);
        service.create(projectId, new LinkInput("BIO", "social", "b2", null, null), null);
        service.create(projectId, new LinkInput("EMAIL", "emails", "e1", null, null), null);

        assertThat(service.list(projectId, "email", null, 10).links()).extracting(TrackedLink::getToken)
                .containsExactly("e1");
        TrackedLinkService.Page page = service.list(projectId, null, null, 2);
        assertThat(page.links()).hasSize(2);
        assertThat(page.total()).isEqualTo(3);
        assertThatThrownBy(() -> service.list(projectId, "BILLBOARD", null, 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(projectId, null, null, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(projectId, null, null, 1001)).isInstanceOf(BusinessException.class);
    }

    // ---- family performance: downloads --------------------------------------------------------------

    @Test
    void aFamilySumsItsTokensDownloadsOverSettledDaysInTheWindowOnly() {
        service.create(projectId, new LinkInput("CREATOR", "creators", "cr-a", null, null), null);
        service.create(projectId, new LinkInput("CREATOR", "creators", "cr-b", null, null), null);
        campaign("cr-a", FROM, 3, false);
        campaign("cr-a", FROM.plusDays(1), 4, false);
        campaign("cr-b", FROM.plusDays(2), 6, false);
        campaign("cr-b", TO.plusDays(1), 50, false);
        campaign("cr-a", FROM.minusDays(1), 40, false);
        campaign("cr-b", TO, 99, true);

        FamilyPerformance creators = family(service.performance(projectId, FROM, TO), "creators");

        assertThat(creators.kind()).isEqualTo("CREATOR");
        assertThat(creators.tokens()).isEqualTo(2);
        assertThat(creators.downloads()).isEqualTo(13L);
        assertThat(creators.belowThreshold()).isFalse();
        assertThat(creators.note()).isNull();
    }

    @Test
    void aFamilyWithNoRowsInTheWindowReadsNullDownloadsBelowThresholdAndTheNoteNeverZero() {
        service.create(projectId, new LinkInput("BIO", "social", "ig-bio", null, null), null);
        service.create(projectId, new LinkInput("BIO", "social", "tt-bio", null, null), null);
        campaign("ig-bio", TO, 7, true);
        campaign("tt-bio", FROM.minusDays(5), 9, false);

        FamilyPerformance social = family(service.performance(projectId, FROM, TO), "social");

        assertThat(social.downloads()).isNull();
        assertThat(social.belowThreshold()).isTrue();
        assertThat(social.note()).isEqualTo("below Apple's reporting threshold");
        assertThat(social.tokens()).isEqualTo(2);
    }

    @Test
    void oneTokenReportingIsEnoughForTheFamilyToShowDownloadsAndTheOthersAddNothing() {
        service.create(projectId, new LinkInput("BIO", "social", "ig-bio", null, null), null);
        service.create(projectId, new LinkInput("BIO", "social", "tt-bio", null, null), null);
        campaign("tt-bio", FROM.plusDays(3), 5, false);

        FamilyPerformance social = family(service.performance(projectId, FROM, TO), "social");

        assertThat(social.downloads()).isEqualTo(5L);
        assertThat(social.belowThreshold()).isFalse();
        assertThat(social.note()).isNull();
    }

    @Test
    void aTokenAppleReportedAsZeroReadsZeroNotBelowThreshold() {
        service.create(projectId, new LinkInput("EMAIL", "emails", "mail-1", null, null), null);
        campaign("mail-1", FROM.plusDays(1), 0, false);

        FamilyPerformance emails = family(service.performance(projectId, FROM, TO), "emails");

        assertThat(emails.downloads()).isEqualTo(0L);
        assertThat(emails.belowThreshold()).isFalse();
        assertThat(emails.note()).isNull();
    }

    @Test
    void onlyStoreCampaignDownloadsOfThisProjectsTokensAreCounted() {
        service.create(projectId, new LinkInput("EMAIL", "emails", "mail-1", null, null), null);
        String other = newProject().getId();
        String otherConnection = connect(other, "app-store-connect", "ACTIVE", "{\"appId\":\"1\",\"providerToken\":\"2\"}");
        put(storeConnectionId, projectId, "STORE", "downloads", "ct:mail-1", FROM, 11, false);
        put(storeConnectionId, projectId, "ADS", "campaign_downloads", "ct:mail-1", FROM, 12, false);
        put(storeConnectionId, projectId, "STORE", "campaign_downloads", "ct:mail-10", FROM, 13, false);
        put(storeConnectionId, projectId, "STORE", "campaign_downloads", "mail-1", FROM, 14, false);
        put(otherConnection, other, "STORE", "campaign_downloads", "ct:mail-1", FROM, 15, false);

        FamilyPerformance emails = family(service.performance(projectId, FROM, TO), "emails");

        assertThat(emails.downloads()).isNull();
        assertThat(emails.belowThreshold()).isTrue();
    }

    @Test
    void aProjectWithNoLinksHasNoFamilies() {
        assertThat(service.performance(projectId, FROM, TO).families()).isEmpty();
    }

    // ---- family performance: views ------------------------------------------------------------------

    @Test
    void thePostFamilySumsTheLatestViewsOfEveryLinkedPostsDestinations() {
        WorkItem one = newPost(project);
        WorkItem two = newPost(project);
        service.create(projectId, new LinkInput("POST", "posts", "post-1", null, one.getId()), null);
        service.create(projectId, new LinkInput("POST", "posts", "post-2", null, two.getId()), null);
        PostPublishTarget instagram = target(one, "instagram");
        PostPublishTarget tiktok = target(one, "tiktok");
        PostPublishTarget facebook = target(two, "facebook");
        snapshot(instagram, "2026-10-10T06", "2026-10-10T06:05:00Z", 10L, false);
        snapshot(instagram, "2026-10-12T06", "2026-10-12T06:05:00Z", 100L, false);
        snapshot(tiktok, "2026-10-12T06", "2026-10-12T06:05:00Z", 250L, false);
        snapshot(facebook, "2026-10-11T06", "2026-10-11T06:05:00Z", 30L, false);
        snapshot(facebook, "2026-10-13T06", "2026-10-13T06:05:00Z", 5000L, true);

        FamilyPerformance posts = family(service.performance(projectId, FROM, TO), "posts");

        assertThat(posts.kind()).isEqualTo("POST");
        assertThat(posts.tokens()).isEqualTo(2);
        assertThat(posts.views()).isEqualTo(380L);
    }

    @Test
    void postViewsAreNullNotZeroUntilAnyLinkedPostHasReported() {
        WorkItem one = newPost(project);
        service.create(projectId, new LinkInput("POST", "posts", "post-1", null, one.getId()), null);
        target(one, "instagram");

        FamilyPerformance posts = family(service.performance(projectId, FROM, TO), "posts");

        assertThat(posts.views()).isNull();
    }

    @Test
    void aSnapshotWithoutAViewsCounterAddsNothingAndLeavesViewsNullWhenItIsTheOnlyOne() {
        WorkItem one = newPost(project);
        service.create(projectId, new LinkInput("POST", "posts", "post-1", null, one.getId()), null);
        snapshot(target(one, "instagram"), "2026-10-12T06", "2026-10-12T06:05:00Z", null, false);

        assertThat(family(service.performance(projectId, FROM, TO), "posts").views()).isNull();
    }

    @Test
    void familiesOfOtherKindsHaveNoViewsEvenWhenTheirWorkItemHasSnapshots() {
        WorkItem creator = newPost(project);
        service.create(projectId, new LinkInput("CREATOR", "creators", "cr-a", null, creator.getId()), null);
        snapshot(target(creator, "tiktok"), "2026-10-12T06", "2026-10-12T06:05:00Z", 900L, false);

        assertThat(family(service.performance(projectId, FROM, TO), "creators").views()).isNull();
    }

    @Test
    void aPostFamilyShowsItsDownloadsNextToItsViews() {
        WorkItem one = newPost(project);
        service.create(projectId, new LinkInput("POST", "posts", "post-1", null, one.getId()), null);
        snapshot(target(one, "instagram"), "2026-10-12T06", "2026-10-12T06:05:00Z", 100L, false);
        campaign("post-1", FROM.plusDays(5), 8, false);

        FamilyPerformance posts = family(service.performance(projectId, FROM, TO), "posts");

        assertThat(posts.downloads()).isEqualTo(8L);
        assertThat(posts.views()).isEqualTo(100L);
    }

    // ---- the window ---------------------------------------------------------------------------------

    @Test
    void theDefaultWindowIsTheLast28SettledDaysEndingOnTheLatestSettledStoreDay() {
        LocalDate today = LocalDate.of(2026, 11, 5);
        put(storeConnectionId, projectId, "STORE", "impressions", "", LocalDate.of(2026, 11, 1), 10, false);
        put(storeConnectionId, projectId, "STORE", "impressions", "", LocalDate.of(2026, 11, 2), 10, true);
        put(storeConnectionId, projectId, "STORE", "impressions", "", LocalDate.of(2026, 11, 3), 10, true);
        put(storeConnectionId, projectId, "ADS", "spend", "", LocalDate.of(2026, 11, 4), 10, false);

        Performance performance = service.performance(projectId, null, null, today);

        assertThat(performance.to()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(performance.from()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void withNoSettledStoreDaysTheDefaultWindowEndsYesterday() {
        Performance performance = service.performance(projectId, null, null, LocalDate.of(2026, 11, 5));

        assertThat(performance.to()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(performance.from()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void aSingleBoundDerivesTheOtherTwentySevenDaysAwayAndAnEmptyOrOversizeWindowIsRefused() {
        LocalDate today = LocalDate.of(2026, 11, 5);

        Performance onlyTo = service.performance(projectId, null, LocalDate.of(2026, 10, 28), today);
        assertThat(onlyTo.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        Performance onlyFrom = service.performance(projectId, LocalDate.of(2026, 10, 20), null, today);
        assertThat(onlyFrom.from()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(onlyFrom.to()).isEqualTo(LocalDate.of(2026, 11, 4));

        assertThatThrownBy(() -> service.performance(projectId, TO, FROM, today))
                .isInstanceOf(BusinessException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> service.performance(projectId, FROM, FROM.plusDays(366), today))
                .isInstanceOf(BusinessException.class).hasMessageContaining("366");
        assertThat(service.performance(projectId, FROM, FROM.plusDays(365), today).families()).isEmpty();
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private FamilyPerformance family(Performance performance, String name) {
        return performance.families().stream().filter(f -> f.family().equals(name)).findFirst().orElseThrow();
    }

    private User newUser() {
        User u = new User();
        u.setFirebaseUid("links-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Links Test");
        return userRepository.save(u);
    }

    private Project newProject() {
        Project p = new Project();
        p.setName("Links Test Project");
        p.setKey("LK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        return projectRepository.save(p);
    }

    private String connect(String project, String connectorId, String status, String configJson) {
        Connection connection = new Connection();
        connection.setProjectId(project);
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId);
        connection.setAuthType("API_KEY");
        connection.setStatus(status);
        connection.setConfigJson(configJson);
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connectionRepository.saveAndFlush(connection).getId();
    }

    private void campaign(String token, LocalDate day, double value, boolean provisional) {
        put(storeConnectionId, projectId, "STORE", "campaign_downloads", "ct:" + token, day, value, provisional);
    }

    private void put(String connectionId, String project, String channel, String metric, String dimension,
                     LocalDate day, double value, boolean provisional) {
        dailyRepository.upsert(UUID.randomUUID().toString(), project, connectionId, "app-store-connect", channel,
                metric, dimension, day, BigDecimal.valueOf(value), provisional);
    }

    private WorkItem newPost(Project in) {
        WorkItem post = new WorkItem();
        post.setProject(in);
        post.setType("POST");
        post.setTitle("A post");
        post.setCurrentStatus("PUBLISHED");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCreatedBy(user);
        post.setSequenceNumber(nextSequence++);
        return workItemRepository.saveAndFlush(post);
    }

    private PostPublishTarget target(WorkItem post, String platform) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(post);
        t.setConnectorId("meta");
        t.setConnectionId(storeConnectionId);
        t.setPlatform(platform);
        t.setLane(PublishLane.APP_MANAGED);
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setPlatformPostId(platform + "-" + UUID.randomUUID());
        t.setFireTime(OffsetDateTime.parse("2026-10-09T10:00:00Z"));
        t.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(t);
    }

    private void snapshot(PostPublishTarget target, String period, String observedAt, Long views,
                          boolean unavailable) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(target.getId());
        m.setWorkItemId(target.getWorkItem().getId());
        m.setProjectId(project.getId());
        m.setPlatform(target.getPlatform());
        m.setPeriodKey(period);
        m.setObservedAt(OffsetDateTime.parse(observedAt));
        m.setViews(views);
        m.setUnavailable(unavailable);
        metricRepository.saveAndFlush(m);
    }
}
