package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Issue;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.AssetResponse;
import com.conductor.generated.model.CreateAssetRequest;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";

    private AssetRepository assetRepository;
    private IssueRepository issueRepository;
    private ProjectSecurityService projectSecurityService;
    private NotificationDispatcher notificationDispatcher;
    private AssetService service;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        issueRepository = Mockito.mock(IssueRepository.class);
        projectSecurityService = Mockito.mock(ProjectSecurityService.class);
        notificationDispatcher = Mockito.mock(NotificationDispatcher.class);
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(
                Mockito.mock(WorkflowDefinitionVersionRepository.class), new ObjectMapper());
        service = new AssetService(assetRepository, issueRepository, projectSecurityService, resolver,
                notificationDispatcher);
        when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
    }

    private Issue issue() {
        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setTitle("T");
        Project project = new Project();
        project.setId(PROJECT_ID);
        issue.setProject(project);
        issue.setWorkflow("ENGINEERING");
        return issue;
    }

    private User caller() {
        User u = new User();
        u.setId("user-1");
        return u;
    }

    @Test
    void createsAllowedAssetTypeAndDispatchesEvent() {
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue()));
        CreateAssetRequest req = new CreateAssetRequest("github_pr", CreateAssetRequest.KindEnum.LINK, "https://x/pr/1");
        req.setLabel("PR #1");

        AssetResponse response = service.createAsset(PROJECT_ID, ISSUE_ID, req, caller());

        assertThat(response.getType()).isEqualTo("github_pr");
        assertThat(response.getKind()).isEqualTo(AssetResponse.KindEnum.LINK);
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(EventType.ASSET_ADDED);
    }

    @Test
    void rejectsAssetTypeNotAllowedByWorkflow() {
        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue()));
        // ENGINEERING only allows github_pr.
        CreateAssetRequest req = new CreateAssetRequest("published_url", CreateAssetRequest.KindEnum.LINK, "https://x");

        assertThatThrownBy(() -> service.createAsset(PROJECT_ID, ISSUE_ID, req, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed by workflow ENGINEERING");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void recordPullRequestAssetIsIdempotent() {
        Issue issue = issue();
        when(assetRepository.existsByIssueIdAndTypeAndRef(ISSUE_ID, "github_pr", "https://x/pr/1")).thenReturn(true);

        service.recordPullRequestAsset(issue, "https://x/pr/1");

        verify(assetRepository, never()).save(any());
    }

    @Test
    void recordPullRequestAssetCreatesGithubPrAsset() {
        Issue issue = issue();
        when(assetRepository.existsByIssueIdAndTypeAndRef(ISSUE_ID, "github_pr", "https://x/pr/1")).thenReturn(false);

        service.recordPullRequestAsset(issue, "https://x/pr/1");

        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("github_pr");
        assertThat(saved.getValue().getKind()).isEqualTo("link");
        assertThat(saved.getValue().isDone()).isTrue();
    }

    @Test
    void recordPullRequestAssetIgnoresBlankUrl() {
        service.recordPullRequestAsset(issue(), "  ");
        verify(assetRepository, never()).save(any());
    }
}
