package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.BusinessException;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.service.view.AssetInput;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private AssetRepository assetRepository;
    private WorkItemRepository workItemRepository;
    private ProjectSecurityService projectSecurityService;
    private NotificationDispatcher notificationDispatcher;
    private AssetService service;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        workItemRepository = Mockito.mock(WorkItemRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        notificationDispatcher = Mockito.mock(NotificationDispatcher.class);
        // Resolution is DB-only: back the resolver with a mock version repo returning the seeded ENGINEERING
        // published snapshot (ENGINEERING allows only the github_pr asset type).
        WorkflowDefinitionVersionRepository versionRepository =
                Mockito.mock(WorkflowDefinitionVersionRepository.class);
        when(versionRepository.findLatestPublished(any(), eq("ENGINEERING")))
                .thenReturn(Optional.of(engineeringSnapshot()));
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository);
        service = new AssetService(assetRepository, workItemRepository, projectSecurityService, resolver,
                notificationDispatcher);
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private WorkflowDefinitionVersion engineeringSnapshot() {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            WorkflowDefinitionVersion v = new WorkflowDefinitionVersion();
            v.setVersion(1);
            v.setDefinition(new ObjectMapper().readTree(in));
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkItem workItem() {
        WorkItem workItem = new WorkItem();
        workItem.setId(ISSUE_ID);
        workItem.setTitle("T");
        Project project = new Project();
        project.setId(PROJECT_ID);
        workItem.setProject(project);
        workItem.setWorkflow("ENGINEERING");
        return workItem;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void createsAllowedAssetTypeAndDispatchesEvent() {
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem()));
        AssetInput req = new AssetInput("github_pr", "PR #1", "link", "https://x/pr/1", null);

        Asset response = service.createAsset(PROJECT_ID, ISSUE_ID, req, caller());

        assertThat(response.getType()).isEqualTo("github_pr");
        assertThat(response.getKind()).isEqualTo("link");
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(EventType.ASSET_ADDED);
    }

    @Test
    void rejectsAssetTypeNotAllowedByWorkflow() {
        when(workItemRepository.findById(ISSUE_ID)).thenReturn(Optional.of(workItem()));
        // ENGINEERING only allows github_pr.
        AssetInput req = new AssetInput("published_url", null, "link", "https://x", null);

        assertThatThrownBy(() -> service.createAsset(PROJECT_ID, ISSUE_ID, req, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed by workflow ENGINEERING");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void recordPullRequestAssetIsIdempotent() {
        WorkItem workItem = workItem();
        when(assetRepository.existsByWorkItemIdAndTypeAndRef(ISSUE_ID, "github_pr", "https://x/pr/1")).thenReturn(true);

        service.recordPullRequestAsset(workItem, "https://x/pr/1");

        verify(assetRepository, never()).save(any());
    }

    @Test
    void recordPullRequestAssetCreatesGithubPrAsset() {
        WorkItem workItem = workItem();
        when(assetRepository.existsByWorkItemIdAndTypeAndRef(ISSUE_ID, "github_pr", "https://x/pr/1")).thenReturn(false);

        service.recordPullRequestAsset(workItem, "https://x/pr/1");

        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("github_pr");
        assertThat(saved.getValue().getKind()).isEqualTo("link");
        assertThat(saved.getValue().isDone()).isTrue();
    }

    @Test
    void recordPullRequestAssetIgnoresBlankUrl() {
        service.recordPullRequestAsset(workItem(), "  ");
        verify(assetRepository, never()).save(any());
    }
}
