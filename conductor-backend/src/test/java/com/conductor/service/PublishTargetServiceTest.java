package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COND-23 T3.6 — the derivation of a project's selectable publish targets and the set-replace that
 * persists a Post's choice among them.
 *
 * <p>Pure unit test per {@code docs/testing-guidelines.md}: everything here is a decision made from
 * connection config plus the existing rows, so a Spring context would only slow it down. The
 * repository-level guarantees these tests deliberately do not re-prove (the unique triple, the unique
 * idempotency key) belong to {@code PostPublishTargetRepositoryTest}.
 */
class PublishTargetServiceTest {

    private static final String PROJECT = "project-1";
    private static final String WORK_ITEM = "post-1";

    private ConnectionRepository connectionRepository;
    private PostPublishTargetRepository targetRepository;
    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private PublishBundleGuard publishBundleGuard;
    private PublishTargetService service;

    private User caller;
    private WorkItem post;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(ConnectionRepository.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        workItemRepository = mock(WorkItemRepository.class);
        projectSecurityService = mock(ProjectSecurityService.class);
        publishBundleGuard = mock(PublishBundleGuard.class);
        service = new PublishTargetService(connectionRepository, targetRepository, workItemRepository,
                projectSecurityService, publishBundleGuard);

        caller = new User();
        caller.setId("user-1");
        when(projectSecurityService.isProjectMember(PROJECT, "user-1")).thenReturn(true);

        Project project = new Project();
        project.setId(PROJECT);
        post = new WorkItem();
        post.setId(WORK_ITEM);
        post.setProject(project);
        post.setCurrentStatus("DRAFT");
        when(workItemRepository.findById(WORK_ITEM)).thenReturn(Optional.of(post));

        connections("meta");
        connections("youtube");
        connections("tiktok");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of());
        when(targetRepository.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));
    }

    // ── derivation ────────────────────────────────────────────────────────────────────────────

    @Test
    void metaConnectionWithLinkedInstagramYieldsBothAFacebookAndAnInstagramTarget() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactly("facebook", "instagram");
        assertThat(options).extracting(PublishTargetService.TargetOption::connectionId)
                .containsOnly("conn-meta");
        assertThat(options.get(0).label()).isEqualTo("Acme Page");
        assertThat(options.get(0).lane()).isEqualTo(PublishLane.NATIVE);
        assertThat(options.get(1).label()).isEqualTo("@acme");
        assertThat(options.get(1).lane()).isEqualTo(PublishLane.APP_MANAGED);
    }

    @Test
    void metaConnectionWithNoLinkedInstagramYieldsFacebookOnly() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactly("facebook");
    }

    @Test
    void aProjectWithNoTikTokConnectionDoesNotListTiktok() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactlyInAnyOrder("facebook", "instagram", "youtube")
                .doesNotContain("tiktok");
    }

    @Test
    void youtubeAndTiktokConnectionsYieldTheirOwnTargetsWithTheirOwnLanes() {
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).extracting(PublishTargetService.TargetOption::platform,
                        PublishTargetService.TargetOption::label, PublishTargetService.TargetOption::lane)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("youtube", "Acme Channel", PublishLane.NATIVE),
                        org.assertj.core.groups.Tuple.tuple("tiktok", "Acme Creator", PublishLane.APP_MANAGED));
    }

    @Test
    void onlyActiveConnectionsAreOffered() {
        Connection disabled = metaConnection("conn-off", "Retired Page", null, null);
        disabled.setStatus("DISABLED");
        connections("meta", disabled);

        assertThat(service.listAvailableTargets(PROJECT, caller)).isEmpty();
    }

    @Test
    void anUnhealthyConnectionIsStillOfferedButCarriesItsHealthMessage() {
        Connection unhealthy = metaConnection("conn-meta", "Acme Page", null, null);
        unhealthy.setHealthStatus("UNHEALTHY");
        unhealthy.setHealthMessage("Session expired, please reconnect");
        connections("meta", unhealthy);

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.healthStatus()).isEqualTo("UNHEALTHY");
            assertThat(option.healthMessage()).isEqualTo("Session expired, please reconnect");
        });
    }

    @Test
    void twoInstagramAccountsAppearAsSeparateTargetsWithDifferentConnectionIds() {
        connections("meta",
                metaConnection("conn-a", "Page A", "ig-a", "acme"),
                metaConnection("conn-b", "Page B", "ig-b", "acme_uk"));

        List<PublishTargetService.TargetOption> instagram = service.listAvailableTargets(PROJECT, caller).stream()
                .filter(o -> "instagram".equals(o.platform()))
                .toList();

        assertThat(instagram).hasSize(2);
        assertThat(instagram).extracting(PublishTargetService.TargetOption::connectionId)
                .containsExactlyInAnyOrder("conn-a", "conn-b");
        assertThat(instagram).extracting(PublishTargetService.TargetOption::label)
                .containsExactlyInAnyOrder("@acme", "@acme_uk");
    }

    @Test
    void aNonMemberIsRefusedTheProjectTargetListing() {
        when(projectSecurityService.isProjectMember(PROJECT, "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service.listAvailableTargets(PROJECT, caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── per-creator options (TIK-3) ───────────────────────────────────────────────────────────

    /**
     * The two values a picker cannot invent. TikTok reports a different privacy set per creator, and the
     * consent step has to name the account by the handle its creator would recognise — so both come off
     * the connection or not at all.
     */
    @Test
    void aTikTokTargetCarriesTheCreatorsOwnPrivacyLevelsAndNickname() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator",
                "PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY"));

        assertThat(service.listAvailableTargets(PROJECT, caller)).singleElement().satisfies(option -> {
            assertThat(option.privacyLevelOptions())
                    .containsExactly("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");
            assertThat(option.creatorNickname()).isEqualTo("Acme Creator");
        });
    }

    @Test
    void everyNonTikTokTargetReportsNoCreatorOptions() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactlyInAnyOrder("facebook", "instagram", "youtube");
        assertThat(options).allSatisfy(option -> {
            assertThat(option.privacyLevelOptions()).isNull();
            assertThat(option.creatorNickname()).isNull();
        });
    }

    /**
     * An account connected before the creator info was cached is a reconnect prompt, not a 500: the
     * listing still answers, with nothing to offer for that one target.
     */
    @Test
    void aTikTokConnectionWithNoCachedCreatorInfoReportsNullRatherThanFailing() {
        connections("tiktok", connection("conn-tt", "tiktok", "{\"creatorUsername\":\"acme\"}"));

        assertThat(service.listAvailableTargets(PROJECT, caller)).singleElement().satisfies(option -> {
            assertThat(option.privacyLevelOptions()).isNull();
            assertThat(option.creatorNickname()).isNull();
            assertThat(option.label()).isEqualTo("acme");
        });
    }

    @Test
    void aCachedPrivacyLevelListThatIsEmptyOrNotAListReportsNull() {
        connections("tiktok",
                connection("conn-a", "tiktok", "{\"creatorNickname\":\"Acme\",\"privacyLevelOptions\":[]}"),
                connection("conn-b", "tiktok",
                        "{\"creatorNickname\":\"Acme UK\",\"privacyLevelOptions\":\"PUBLIC_TO_EVERYONE\"}"));

        assertThat(service.listAvailableTargets(PROJECT, caller)).hasSize(2)
                .allSatisfy(option -> assertThat(option.privacyLevelOptions()).isNull());
    }

    // ── selection ─────────────────────────────────────────────────────────────────────────────

    @Test
    void savingASelectionWritesOneRowPerChosenTargetWithTheCorrectLane() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        post.setScheduledFor(OffsetDateTime.parse("2026-09-01T12:00:00Z"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta"),
                        new PublishTargetService.TargetSelection("instagram", "conn-meta")),
                caller);

        List<PostPublishTarget> saved = savedRows();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(PostPublishTarget::getPlatform, PostPublishTarget::getLane)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("facebook", PublishLane.NATIVE),
                        org.assertj.core.groups.Tuple.tuple("instagram", PublishLane.APP_MANAGED));
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getConnectorId()).isEqualTo("meta");
            assertThat(row.getConnectionId()).isEqualTo("conn-meta");
            assertThat(row.getState()).isEqualTo(PostPublishTargetState.PENDING);
            assertThat(row.getFireTime()).isEqualTo(OffsetDateTime.parse("2026-09-01T12:00:00Z"));
            assertThat(row.getIdempotencyKey()).isNotBlank();
        });
        assertThat(saved).extracting(PostPublishTarget::getIdempotencyKey).doesNotHaveDuplicates();
        assertThat(saved).extracting(PostPublishTarget::getPlatformAccountLabel)
                .containsExactlyInAnyOrder("Acme Page", "@acme");
    }

    @Test
    void selectingOneOfTwoInstagramAccountsPersistsOnlyThatConnection() {
        connections("meta",
                metaConnection("conn-a", "Page A", "ig-a", "acme"),
                metaConnection("conn-b", "Page B", "ig-b", "acme_uk"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("instagram", "conn-b")), caller);

        assertThat(savedRows()).singleElement().satisfies(row -> {
            assertThat(row.getPlatform()).isEqualTo("instagram");
            assertThat(row.getConnectionId()).isEqualTo("conn-b");
        });
    }

    @Test
    void reSavingAnUnchangedSelectionDoesNotRecreateRows() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget handedOff = existingRow("facebook", "meta", "conn-meta");
        handedOff.setState(PostPublishTargetState.HANDED_OFF);
        handedOff.setPlatformPostId("fb-post-9");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(handedOff));

        List<PostPublishTarget> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        verify(targetRepository, never()).deleteAll(any());
        assertThat(savedRows()).isEmpty();
        assertThat(result).containsExactly(handedOff);
        assertThat(handedOff.getPlatformPostId()).isEqualTo("fb-post-9");
        assertThat(handedOff.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void reSavingAnUnchangedSelectionDoesNotRevertThePost() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        when(targetRepository.findAllByWorkItemId(WORK_ITEM))
                .thenReturn(List.of(existingRow("facebook", "meta", "conn-meta")));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        verify(publishBundleGuard, never()).revertForBundleEdit(anyString(), any());
    }

    @Test
    void deselectingATargetRemovesItsRow() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        PostPublishTarget facebook = existingRow("facebook", "meta", "conn-meta");
        PostPublishTarget instagram = existingRow("instagram", "meta", "conn-meta");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(facebook, instagram));

        List<PostPublishTarget> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        ArgumentCaptor<List<PostPublishTarget>> removed = captor();
        verify(targetRepository).deleteAll(removed.capture());
        assertThat(removed.getValue()).containsExactly(instagram);
        assertThat(result).containsExactly(facebook);
    }

    @Test
    void anEmptySelectionClearsEveryTarget() {
        PostPublishTarget facebook = existingRow("facebook", "meta", "conn-meta");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(facebook));

        assertThat(service.replaceSelection(PROJECT, WORK_ITEM, List.of(), caller)).isEmpty();

        ArgumentCaptor<List<PostPublishTarget>> removed = captor();
        verify(targetRepository).deleteAll(removed.capture());
        assertThat(removed.getValue()).containsExactly(facebook);
    }

    @Test
    void editingTheSelectionRevertsAnApprovedPostThroughThePublishBundleGuard() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        post.setCurrentStatus("APPROVED");

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
    }

    @Test
    void theRevertRunsBeforeAnyRowIsWritten() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        when(publishBundleGuard.revertForBundleEdit(eq(PROJECT), any()))
                .thenThrow(new BusinessException("Could not revoke the scheduled post"));

        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller))
                .isInstanceOf(BusinessException.class);

        verify(targetRepository, never()).saveAll(any());
        verify(targetRepository, never()).deleteAll(any());
    }

    @Test
    void aTargetTheProjectCannotPublishToIsRefused() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));

        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("instagram", "conn-meta")), caller))
                .isInstanceOf(BusinessException.class);

        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void aConnectionBelongingToAnotherProjectIsRefused() {
        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "someone-elses-connection")), caller))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aDuplicatedSelectionYieldsOneRow() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta"),
                        new PublishTargetService.TargetSelection("facebook", "conn-meta")),
                caller);

        assertThat(savedRows()).hasSize(1);
    }

    @Test
    void aNonMemberIsRefusedTheSelectionWrite() {
        when(projectSecurityService.isProjectMember(PROJECT, "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM, List.of(), caller))
                .isInstanceOf(EntityNotFoundException.class);

        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void aWorkItemInAnotherProjectIsNotFound() {
        Project other = new Project();
        other.setId("project-2");
        post.setProject(other);

        assertThatThrownBy(() -> service.listSelectedTargets(PROJECT, WORK_ITEM, caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private void connections(String connectorId, Connection... rows) {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT, connectorId)).thenReturn(List.of(rows));
    }

    private static Connection connection(String id, String connectorId, String configJson) {
        Connection connection = new Connection();
        connection.setId(id);
        connection.setProjectId(PROJECT);
        connection.setConnectorId(connectorId);
        connection.setStatus("ACTIVE");
        connection.setConfigJson(configJson);
        return connection;
    }

    private static Connection metaConnection(String id, String pageName, String igAccountId, String igUsername) {
        StringBuilder config = new StringBuilder("{\"pageId\":\"page-" + id + "\",\"pageName\":\"" + pageName + "\"");
        if (igAccountId != null) {
            config.append(",\"instagramBusinessAccountId\":\"").append(igAccountId).append('"');
        }
        if (igUsername != null) {
            config.append(",\"instagramUsername\":\"").append(igUsername).append('"');
        }
        return connection(id, "meta", config.append('}').toString());
    }

    private static Connection youtubeConnection(String id, String channelTitle) {
        return connection(id, "youtube",
                "{\"channelId\":\"chan-" + id + "\",\"channelTitle\":\"" + channelTitle + "\"}");
    }

    /** A TikTok connection; with no levels it stands in for one connected before they were cached. */
    private static Connection tiktokConnection(String id, String creatorNickname, String... privacyLevels) {
        StringBuilder config = new StringBuilder("{\"creatorNickname\":\"" + creatorNickname
                + "\",\"creatorUsername\":\"acme\"");
        if (privacyLevels.length > 0) {
            config.append(",\"privacyLevelOptions\":[\"").append(String.join("\",\"", privacyLevels))
                    .append("\"]");
        }
        return connection(id, "tiktok", config.append('}').toString());
    }

    private PostPublishTarget existingRow(String platform, String connectorId, String connectionId,
                                          String publishOptions) {
        PostPublishTarget target = existingRow(platform, connectorId, connectionId);
        target.setPublishOptions(publishOptions);
        return target;
    }

    private PostPublishTarget existingRow(String platform, String connectorId, String connectionId) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-" + platform + "-" + connectionId);
        target.setWorkItem(post);
        target.setPlatform(platform);
        target.setConnectorId(connectorId);
        target.setConnectionId(connectionId);
        target.setLane("instagram".equals(platform) || "tiktok".equals(platform)
                ? PublishLane.APP_MANAGED : PublishLane.NATIVE);
        target.setState(PostPublishTargetState.PENDING);
        target.setIdempotencyKey("pub:" + WORK_ITEM + ":" + platform + ":" + connectionId);
        return target;
    }

    private List<PostPublishTarget> savedRows() {
        ArgumentCaptor<List<PostPublishTarget>> saved = captor();
        verify(targetRepository, org.mockito.Mockito.atMost(1)).saveAll(saved.capture());
        return saved.getAllValues().isEmpty() ? List.of() : saved.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<PostPublishTarget>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
    // --- restampFireTimes: a rescheduled Post must not fire its targets at the old time ---

    @Test
    void restampFireTimesUpdatesPendingTargetsToThePostsCurrentSchedule() {
        WorkItem post = new WorkItem();
        post.setId("wi-restamp");
        OffsetDateTime original = OffsetDateTime.parse("2026-09-01T09:00:00Z");
        OffsetDateTime rescheduled = OffsetDateTime.parse("2026-09-04T17:30:00Z");
        post.setScheduledFor(rescheduled);

        PostPublishTarget pending = new PostPublishTarget();
        pending.setId("t-pending");
        pending.setState(PostPublishTargetState.PENDING);
        pending.setFireTime(original);

        when(targetRepository.findAllByWorkItemIdAndState("wi-restamp", PostPublishTargetState.PENDING))
                .thenReturn(List.of(pending));

        int restamped = service.restampFireTimes(post);

        assertThat(restamped).isEqualTo(1);
        assertThat(pending.getFireTime()).isEqualTo(rescheduled);
        verify(targetRepository).save(pending);
    }

    /**
     * A HANDED_OFF row carries platform-side state (the scheduled post already exists on Facebook/YouTube at
     * the old time), so silently re-timing it would desynchronise the two. Those rows are revoked and
     * re-created on any exit from Scheduled instead — the query itself is scoped to PENDING.
     */
    @Test
    void restampFireTimesOnlyTouchesPendingTargets() {
        WorkItem post = new WorkItem();
        post.setId("wi-restamp");
        post.setScheduledFor(OffsetDateTime.parse("2026-09-04T17:30:00Z"));
        when(targetRepository.findAllByWorkItemIdAndState("wi-restamp", PostPublishTargetState.PENDING))
                .thenReturn(List.of());

        assertThat(service.restampFireTimes(post)).isZero();

        verify(targetRepository, never()).findAllByWorkItemIdAndState(
                "wi-restamp", PostPublishTargetState.HANDED_OFF);
        verify(targetRepository, never()).save(any());
    }

    @Test
    void restampFireTimesIsANoOpForAnUnscheduledPostAndDoesNotRewriteAnUnchangedFireTime() {
        WorkItem unscheduled = new WorkItem();
        unscheduled.setId("wi-restamp");
        assertThat(service.restampFireTimes(unscheduled)).isZero();
        assertThat(service.restampFireTimes(null)).isZero();

        WorkItem post = new WorkItem();
        post.setId("wi-restamp");
        OffsetDateTime same = OffsetDateTime.parse("2026-09-04T17:30:00Z");
        post.setScheduledFor(same);
        PostPublishTarget unchanged = new PostPublishTarget();
        unchanged.setState(PostPublishTargetState.PENDING);
        unchanged.setFireTime(same);
        when(targetRepository.findAllByWorkItemIdAndState("wi-restamp", PostPublishTargetState.PENDING))
                .thenReturn(List.of(unchanged));

        assertThat(service.restampFireTimes(post)).isZero();
        verify(targetRepository, never()).save(any());
    }


    // ── publish options ───────────────────────────────────────────────────────────────────────

    /**
     * The whole point of TIK-1: before this, a TikTok target carried nowhere to say who could see the post,
     * so every one of them published SELF_ONLY and succeeded.
     */
    @Test
    void aTargetsPublishOptionsArePersistedOnItsRow() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        new LinkedHashMap<>(Map.of("privacyLevel", "PUBLIC_TO_EVERYONE",
                                "disableComment", true, "brandContentToggle", false)))),
                caller);

        assertThat(savedRows()).singleElement().satisfies(row ->
                assertThat(row.getPublishOptions())
                        .isEqualTo("{\"brandContentToggle\":false,\"disableComment\":true,"
                                + "\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
    }

    @Test
    void aTargetChosenWithNoPublishOptionsStoresNone() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt")), caller);

        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.getPublishOptions()).isNull());
    }

    @Test
    void anEmptyOptionsBagStoresNoneRatherThanEmptyJson() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt", Map.of())), caller);

        assertThat(savedRows()).singleElement()
                .satisfies(row -> assertThat(row.getPublishOptions()).isNull());
    }

    /**
     * An options edit keeps the row: the target still publishes to the same account under the same
     * idempotency key, and deleting it would strand any platform post the row is the only handle on.
     */
    @Test
    void changingATargetsOptionsUpdatesItsRowInPlaceInsteadOfRecreatingIt() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        PostPublishTarget existing = existingRow("tiktok", "tiktok", "conn-tt",
                "{\"privacyLevel\":\"SELF_ONLY\"}");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        List<PostPublishTarget> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        Map.of("privacyLevel", "PUBLIC_TO_EVERYONE"))), caller);

        verify(targetRepository, never()).deleteAll(any());
        assertThat(result).containsExactly(existing);
        assertThat(existing.getPublishOptions()).isEqualTo("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
        assertThat(savedRows()).containsExactly(existing);
    }

    @Test
    void clearingATargetsOptionsIsAChangeAndIsPersisted() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        PostPublishTarget existing = existingRow("tiktok", "tiktok", "conn-tt",
                "{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt")), caller);

        assertThat(existing.getPublishOptions()).isNull();
        assertThat(savedRows()).containsExactly(existing);
    }

    /** [auto] Changing a target's options reverts an Approved Post, like any other bundle change. */
    @Test
    void changingATargetsOptionsRevertsAnApprovedPostThroughThePublishBundleGuard() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        post.setCurrentStatus("APPROVED");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM))
                .thenReturn(List.of(existingRow("tiktok", "tiktok", "conn-tt",
                        "{\"privacyLevel\":\"SELF_ONLY\"}")));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        Map.of("privacyLevel", "PUBLIC_TO_EVERYONE"))), caller);

        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
    }

    @Test
    void theRevertRunsBeforeAnyOptionsAreWritten() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        PostPublishTarget existing = existingRow("tiktok", "tiktok", "conn-tt",
                "{\"privacyLevel\":\"SELF_ONLY\"}");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));
        when(publishBundleGuard.revertForBundleEdit(eq(PROJECT), any()))
                .thenThrow(new BusinessException("Could not revoke the scheduled post"));

        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        Map.of("privacyLevel", "PUBLIC_TO_EVERYONE"))), caller))
                .isInstanceOf(BusinessException.class);

        assertThat(existing.getPublishOptions()).isEqualTo("{\"privacyLevel\":\"SELF_ONLY\"}");
        verify(targetRepository, never()).saveAll(any());
    }

    /** Re-sending the same choices in a different key order must not knock a Post out of Approved. */
    @Test
    void reSendingTheSameOptionsInADifferentOrderIsNotAChange() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        PostPublishTarget existing = existingRow("tiktok", "tiktok", "conn-tt",
                "{\"disableComment\":true,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("privacyLevel", "PUBLIC_TO_EVERYONE");
        reordered.put("disableComment", true);

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt", reordered)), caller);

        verify(publishBundleGuard, never()).revertForBundleEdit(anyString(), any());
        verify(targetRepository, never()).saveAll(any());
        verify(targetRepository, never()).deleteAll(any());
    }

    @Test
    void aNewTargetAndAReOptionedOneAreBothWrittenInOnePass() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));
        PostPublishTarget tiktok = existingRow("tiktok", "tiktok", "conn-tt",
                "{\"privacyLevel\":\"SELF_ONLY\"}");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(tiktok));

        List<PostPublishTarget> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                                Map.of("privacyLevel", "PUBLIC_TO_EVERYONE")),
                        new PublishTargetService.TargetSelection("facebook", "conn-meta")),
                caller);

        assertThat(result).extracting(PostPublishTarget::getPlatform)
                .containsExactly("facebook", "tiktok");
        assertThat(savedRows()).extracting(PostPublishTarget::getPlatform)
                .containsExactly("tiktok", "facebook");
        assertThat(tiktok.getPublishOptions()).isEqualTo("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
    }

    /** Options chosen on a save have to survive a reload, or the picker re-renders them as unchosen. */
    @Test
    void optionsChosenOnASaveAreWhatTheSelectedTargetsListingReportsBack() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator", "PUBLIC_TO_EVERYONE"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        Map.of("privacyLevel", "PUBLIC_TO_EVERYONE", "disableComment", true))), caller);
        List<PostPublishTarget> persisted = savedRows();
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(persisted);

        assertThat(service.listSelectedTargets(PROJECT, WORK_ITEM, caller)).singleElement()
                .satisfies(row -> assertThat(row.getPublishOptions())
                        .isEqualTo("{\"disableComment\":true,\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}"));
    }

    @Test
    void aTargetSavedWithoutOptionsListsBackWithNone() {
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator", "PUBLIC_TO_EVERYONE"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt")), caller);
        List<PostPublishTarget> persisted = savedRows();
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(persisted);

        assertThat(service.listSelectedTargets(PROJECT, WORK_ITEM, caller)).singleElement()
                .satisfies(row -> assertThat(row.getPublishOptions()).isNull());
    }

}
