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
import java.util.List;
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

    private static Connection tiktokConnection(String id, String creatorNickname) {
        return connection(id, "tiktok",
                "{\"creatorNickname\":\"" + creatorNickname + "\",\"creatorUsername\":\"acme\"}");
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
}
