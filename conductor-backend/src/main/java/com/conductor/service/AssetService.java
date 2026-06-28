package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Issue;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.AssetResponse;
import com.conductor.generated.model.CreateAssetRequest;
import com.conductor.generated.model.PatchAssetRequest;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Produced-output Assets on a Work Item (COND-18 E5). Mirrors the {@code comments} service template:
 * fat service owning {@code @Transactional}, membership/role checks, the asset-type guard against the bound
 * Workflow's {@code asset_types}, notification dispatch, and entity→DTO assembly.
 */
@Service
public class AssetService {

    private final AssetRepository assetRepository;
    private final IssueRepository issueRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final NotificationDispatcher notificationDispatcher;

    public AssetService(AssetRepository assetRepository,
                        IssueRepository issueRepository,
                        ProjectSecurityService projectSecurityService,
                        WorkflowDefinitionResolver resolver,
                        NotificationDispatcher notificationDispatcher) {
        this.assetRepository = assetRepository;
        this.issueRepository = issueRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> listAssets(String projectId, String issueId, User caller) {
        verifyMembership(projectId, caller.getId());
        findIssueInProject(projectId, issueId);
        return assetRepository.findAllByIssueId(issueId).stream().map(this::toAssetResponse).toList();
    }

    @Transactional
    public AssetResponse createAsset(String projectId, String issueId, CreateAssetRequest request, User caller) {
        verifyMembership(projectId, caller.getId());
        Issue issue = findIssueInProject(projectId, issueId);
        validateAssetType(projectId, issue, request.getType());

        Asset asset = new Asset();
        asset.setIssue(issue);
        asset.setType(request.getType());
        asset.setLabel(request.getLabel());
        asset.setKind(request.getKind().getValue());
        asset.setRef(request.getRef());
        asset.setDone(Boolean.TRUE.equals(request.getDone()));
        assetRepository.save(asset);

        notificationDispatcher.dispatch(NotificationEvent.of(EventType.ASSET_ADDED, projectId,
                Map.of("issueId", issue.getId(), "issueTitle", issue.getTitle(), "assetType", asset.getType())));
        return toAssetResponse(asset);
    }

    @Transactional
    public AssetResponse patchAsset(String projectId, String issueId, String assetId,
                                    PatchAssetRequest request, User caller) {
        verifyMembership(projectId, caller.getId());
        findIssueInProject(projectId, issueId);
        Asset asset = findAssetInIssue(issueId, assetId);
        if (request.getLabel() != null) {
            asset.setLabel(request.getLabel());
        }
        if (request.getRef() != null) {
            asset.setRef(request.getRef());
        }
        if (request.getDone() != null) {
            asset.setDone(request.getDone());
        }
        assetRepository.save(asset);
        return toAssetResponse(asset);
    }

    @Transactional
    public void deleteAsset(String projectId, String issueId, String assetId, User caller) {
        verifyMembership(projectId, caller.getId());
        findIssueInProject(projectId, issueId);
        Asset asset = findAssetInIssue(issueId, assetId);
        assetRepository.delete(asset);
    }

    /**
     * System path: record a {@code github_pr} Asset when a PR merges (called from
     * {@code IssueService.completeFromPullRequest}). No caller/membership check; idempotent on (issue, type, ref).
     */
    @Transactional
    public void recordPullRequestAsset(Issue issue, String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            return;
        }
        if (assetRepository.existsByIssueIdAndTypeAndRef(issue.getId(), "github_pr", pullRequestUrl)) {
            return;
        }
        Asset asset = new Asset();
        asset.setIssue(issue);
        asset.setType("github_pr");
        asset.setLabel("Pull Request");
        asset.setKind("link");
        asset.setRef(pullRequestUrl);
        asset.setDone(true);
        assetRepository.save(asset);
    }

    private void validateAssetType(String projectId, Issue issue, String type) {
        String slug = issue.getWorkflow() != null ? issue.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug);
        List<String> allowed = statechart.assetTypes();
        if (!allowed.isEmpty() && !allowed.contains(type)) {
            throw new BusinessException("Asset type '" + type + "' is not allowed by workflow " + slug);
        }
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Issue not found");
        }
    }

    private Issue findIssueInProject(String projectId, String issueId) {
        return issueRepository.findById(issueId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
    }

    private Asset findAssetInIssue(String issueId, String assetId) {
        return assetRepository.findByIdAndIssueId(assetId, issueId)
                .orElseThrow(() -> new EntityNotFoundException("Asset not found in issue"));
    }

    private AssetResponse toAssetResponse(Asset asset) {
        AssetResponse response = new AssetResponse(
                asset.getId(),
                asset.getIssue().getId(),
                asset.getType(),
                AssetResponse.KindEnum.fromValue(asset.getKind()),
                asset.getRef(),
                asset.isDone(),
                asset.getCreatedAt(),
                asset.getUpdatedAt());
        response.setLabel(asset.getLabel());
        return response;
    }
}
