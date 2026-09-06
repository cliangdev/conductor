package com.conductor.service;

import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
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
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;

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

    private AssetRepository assetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
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
        targetAssetRepository = mock(PostPublishTargetAssetRepository.class);
        assetRepository = mock(AssetRepository.class);
        service = new PublishTargetService(new PublishPlatformRegistry(), connectionRepository, targetRepository, targetAssetRepository,
                assetRepository, workItemRepository, projectSecurityService, publishBundleGuard,
                new PublishTargetMediaResolver(assetRepository, targetAssetRepository));

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

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

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

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactly("facebook");
    }

    @Test
    void aProjectWithNoTikTokConnectionDoesNotListTiktok() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactlyInAnyOrder("facebook", "instagram", "youtube")
                .doesNotContain("tiktok");
    }

    @Test
    void youtubeAndTiktokConnectionsYieldTheirOwnTargetsWithTheirOwnLanes() {
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));
        connections("tiktok", tiktokConnection("conn-tt", "Acme Creator"));

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

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

        assertThat(connectedOnly(service.listAvailableTargets(PROJECT, caller))).isEmpty();
    }

    @Test
    void anUnhealthyConnectionIsStillOfferedButCarriesItsHealthMessage() {
        Connection unhealthy = metaConnection("conn-meta", "Acme Page", null, null);
        unhealthy.setHealthStatus("UNHEALTHY");
        unhealthy.setHealthMessage("Session expired, please reconnect");
        connections("meta", unhealthy);

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

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

        List<PublishTargetService.TargetOption> instagram = connectedOnly(service.listAvailableTargets(PROJECT, caller)).stream()
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

        assertThat(connectedOnly(service.listAvailableTargets(PROJECT, caller))).singleElement().satisfies(option -> {
            assertThat(option.privacyLevelOptions())
                    .containsExactly("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "SELF_ONLY");
            assertThat(option.creatorNickname()).isEqualTo("Acme Creator");
        });
    }

    @Test
    void everyNonTikTokTargetReportsNoCreatorOptions() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        connections("youtube", youtubeConnection("conn-yt", "Acme Channel"));

        List<PublishTargetService.TargetOption> options = connectedOnly(service.listAvailableTargets(PROJECT, caller));

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

        assertThat(connectedOnly(service.listAvailableTargets(PROJECT, caller))).singleElement().satisfies(option -> {
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

        assertThat(connectedOnly(service.listAvailableTargets(PROJECT, caller))).hasSize(2)
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

        List<PublishTargetService.TargetView> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        verify(targetRepository, never()).deleteAll(any());
        assertThat(savedRows()).isEmpty();
        assertThat(result).extracting(PublishTargetService.TargetView::target).containsExactly(handedOff);
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

        List<PublishTargetService.TargetView> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);

        ArgumentCaptor<List<PostPublishTarget>> removed = captor();
        verify(targetRepository).deleteAll(removed.capture());
        assertThat(removed.getValue()).containsExactly(instagram);
        assertThat(result).extracting(PublishTargetService.TargetView::target).containsExactly(facebook);
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

    // --- Per-target caption and media are part of the selection, and editing them is a bundle edit ---

    @Test
    void aCaptionOverrideIsStoredOnTheRowAndCountsAsABundleEdit() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null,
                        "Just for Facebook", null)), caller);

        assertThat(existing.getCaptionOverride()).isEqualTo("Just for Facebook");
        // Changing the copy one destination publishes is changing what a reviewer approved.
        verify(publishBundleGuard).refuseTargetEditWhileFrozen(PROJECT, post);
        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
    }

    @Test
    void aBlankCaptionOverrideClearsItRatherThanPublishingNothing() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        existing.setCaptionOverride("Old copy");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, "   ", null)),
                caller);

        assertThat(existing.getCaptionOverride()).isNull();
    }

    @Test
    void anAssetSelectionIsStoredInOrderAndMarksTheTargetCustom() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));
        givenPostAssets("asset-a", "asset-b", "asset-c");

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null,
                        List.of("asset-c", "asset-a"))), caller);

        assertThat(existing.isCustomMedia()).isTrue();
        verify(targetAssetRepository).deleteAllByTargetId(existing.getId());
        assertThat(savedSelectionOf(existing)).containsExactly("asset-c", "asset-a");
    }

    @Test
    void anEmptyAssetListMeansInheritRatherThanPublishNothing() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        existing.setCustomMedia(true);
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));
        when(targetAssetRepository.findAllByTargetId(existing.getId()))
                .thenReturn(List.of(new PostPublishTargetAsset(existing.getId(), "asset-a", 0)));
        givenPostAssets("asset-a");

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null,
                        List.of())), caller);

        assertThat(existing.isCustomMedia()).isFalse();
        verify(targetAssetRepository).deleteAllByTargetId(existing.getId());
    }

    @Test
    void reorderingASelectionIsAChange_notANoOp() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        existing.setCustomMedia(true);
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));
        when(targetAssetRepository.findAllByTargetId(existing.getId())).thenReturn(List.of(
                new PostPublishTargetAsset(existing.getId(), "asset-a", 0),
                new PostPublishTargetAsset(existing.getId(), "asset-b", 1)));
        givenPostAssets("asset-a", "asset-b");

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null,
                        List.of("asset-b", "asset-a"))), caller);

        // Order is content — Instagram crops a carousel to its first item — so a swap must revert the Post.
        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
        assertThat(savedSelectionOf(existing)).containsExactly("asset-b", "asset-a");
    }

    @Test
    void resendingTheIdenticalSelectionChangesNothingAndNeverRevertsThePost() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        existing.setCustomMedia(true);
        existing.setCaptionOverride("Just for Facebook");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));
        when(targetAssetRepository.findAllByTargetId(existing.getId())).thenReturn(List.of(
                new PostPublishTargetAsset(existing.getId(), "asset-a", 0),
                new PostPublishTargetAsset(existing.getId(), "asset-b", 1)));
        givenPostAssets("asset-a", "asset-b");

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null,
                        "Just for Facebook", List.of("asset-a", "asset-b"))), caller);

        verify(publishBundleGuard, never()).revertForBundleEdit(any(), any());
        verify(targetAssetRepository, never()).deleteAllByTargetId(any());
    }

    @Test
    void anAssetThatIsNotOnThisPostIsRefused() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of());
        givenPostAssets("asset-a");

        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null,
                        List.of("asset-from-another-post"))), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not a media asset on this Post");
    }

    /** The Post's own file assets, which a per-target selection may name. */
    private void givenPostAssets(String... assetIds) {
        List<Asset> assets = new ArrayList<>();
        for (String id : assetIds) {
            Asset asset = new Asset();
            asset.setId(id);
            asset.setKind(AssetService.KIND_FILE);
            asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
            assets.add(asset);
        }
        when(assetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(assets);
    }

    /** The ordered asset ids written for {@code target}, read off the join-table save. */
    private List<String> savedSelectionOf(PostPublishTarget target) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PostPublishTargetAsset>> saved = ArgumentCaptor.forClass(List.class);
        verify(targetAssetRepository).saveAll(saved.capture());
        return saved.getValue().stream()
                .filter(row -> target.getId().equals(row.getTargetId()))
                .sorted(java.util.Comparator.comparingInt(PostPublishTargetAsset::getPosition))
                .map(PostPublishTargetAsset::getAssetId)
                .toList();
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

        List<PublishTargetService.TargetView> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                        Map.of("privacyLevel", "PUBLIC_TO_EVERYONE"))), caller);

        verify(targetRepository, never()).deleteAll(any());
        assertThat(result).extracting(PublishTargetService.TargetView::target).containsExactly(existing);
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

        List<PublishTargetService.TargetView> result = service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", "conn-tt",
                                Map.of("privacyLevel", "PUBLIC_TO_EVERYONE")),
                        new PublishTargetService.TargetSelection("facebook", "conn-meta")),
                caller);

        assertThat(result).extracting(view -> view.target().getPlatform())
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


    /**
     * The options a project's own connections produce, with the always-present manual destinations dropped.
     *
     * <p>Every test that uses it was written to pin down what connecting (or not connecting) an account
     * yields, and a MANUAL option is by definition not derived from a connection — it is offered whether or
     * not one exists. Filtering here keeps each of those assertions saying the thing it was written to say,
     * rather than restating it as "the connected ones, plus the four that are always there".
     */
    private static List<PublishTargetService.TargetOption> connectedOnly(
            List<PublishTargetService.TargetOption> options) {
        return options.stream().filter(option -> option.lane() != PublishLane.MANUAL).toList();
    }

    @Test
    void aProjectWithNoConnectionsAtAllStillHasSomewhereToPublish() {
        // The case the manual lane exists for. Without it this returns nothing, PostScheduleValidator
        // refuses the approval gate for want of a target, and a Post can never leave In Review.
        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).isNotEmpty();
        assertThat(options).allMatch(option -> option.lane() == PublishLane.MANUAL);
        assertThat(options).extracting(PublishTargetService.TargetOption::platform)
                .containsExactlyInAnyOrder("facebook", "instagram", "youtube", "tiktok");
    }

    @Test
    void everyManualOptionCarriesNoAccountAndNoCredentialHealth() {
        List<PublishTargetService.TargetOption> manual = service.listAvailableTargets(PROJECT, caller).stream()
                .filter(option -> option.lane() == PublishLane.MANUAL)
                .toList();

        assertThat(manual).isNotEmpty();
        // No account behind it: a null connection is what the DB CHECK constraint ties to the MANUAL lane,
        // and what tells the picker there is no credential that could be healthy or unhealthy.
        assertThat(manual).allSatisfy(option -> {
            assertThat(option.connectionId()).isNull();
            assertThat(option.connectorId()).isNull();
            assertThat(option.healthStatus()).isNull();
            assertThat(option.healthMessage()).isNull();
            assertThat(option.label()).contains("manual");
        });
    }

    @Test
    void manualDestinationsAreOfferedAlongsideConnectedOnesRatherThanOnlyAsAFallback() {
        // A connected project keeps its manual options: a Story, a personal account or any surface the API
        // does not reach still belongs on the calendar under the same review gate.
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));

        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);

        assertThat(options).filteredOn(option -> option.lane() == PublishLane.MANUAL).hasSize(4);
        assertThat(options).filteredOn(option -> option.lane() != PublishLane.MANUAL)
                .extracting(PublishTargetService.TargetOption::platform)
                .containsExactly("facebook", "instagram");
    }

    // ---- formats: feed, reel, story -------------------------------------------------------------

    @Test
    void aStoryOnAConnectedFacebookPageIsHeldByConductorRatherThanHandedOff() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        post.setScheduledFor(OffsetDateTime.parse("2026-09-01T12:00:00Z"));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null, null, "story"),
                        new PublishTargetService.TargetSelection("instagram", "conn-meta", null, null, null, "reel")),
                caller);

        assertThat(savedRows()).extracting(PostPublishTarget::getPlatform, PostPublishTarget::getFormat,
                        PostPublishTarget::getLane)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("facebook", "STORY", PublishLane.APP_MANAGED),
                        org.assertj.core.groups.Tuple.tuple("instagram", "REEL", PublishLane.APP_MANAGED));
    }

    @Test
    void anAbsentFormatMeansFeed_andAFeedOnlyPlatformRefusesAnyOther() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta")), caller);
        assertThat(savedRows()).extracting(PostPublishTarget::getFormat).containsExactly("FEED");

        // The MANUAL TikTok destination exists for every project, so this is refused on format, not on account.
        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("tiktok", null, null, null, null, "story")), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TikTok does not publish stories")
                .hasMessageContaining("feed");
        assertThatThrownBy(() -> service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null, null, "live")), caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a post format");
    }

    @Test
    void changingAFormatIsABundleEdit_resendingTheSameFormatIsNot() {
        connections("meta", metaConnection("conn-meta", "Acme Page", null, null));
        PostPublishTarget existing = existingRow("facebook", "meta", "conn-meta");
        existing.setFormat("REEL");
        when(targetRepository.findAllByWorkItemId(WORK_ITEM)).thenReturn(List.of(existing));

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null, null, "reel")), caller);
        verify(publishBundleGuard, never()).revertForBundleEdit(PROJECT, post);

        service.replaceSelection(PROJECT, WORK_ITEM,
                List.of(new PublishTargetService.TargetSelection("facebook", "conn-meta", null, null, null, "story")), caller);
        assertThat(existing.getFormat()).isEqualTo("STORY");
        assertThat(existing.getLane()).isEqualTo(PublishLane.APP_MANAGED);
        verify(publishBundleGuard).revertForBundleEdit(PROJECT, post);
    }

    @Test
    void everyOptionRowListsTheFormatsItsPlatformOffers() {
        connections("meta", metaConnection("conn-meta", "Acme Page", "ig-42", "acme"));
        List<PublishTargetService.TargetOption> options = service.listAvailableTargets(PROJECT, caller);
        assertThat(options).filteredOn(o -> "facebook".equals(o.platform()))
                .allSatisfy(o -> assertThat(o.formats()).containsExactly("feed", "reel", "story"));
        assertThat(options).filteredOn(o -> "tiktok".equals(o.platform()))
                .allSatisfy(o -> assertThat(o.formats()).containsExactly("feed"));
    }
}
