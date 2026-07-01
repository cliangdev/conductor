package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.AssetInput;
import com.conductor.service.view.AssetPatch;
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
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final NotificationDispatcher notificationDispatcher;

    public AssetService(AssetRepository assetRepository,
                        WorkItemRepository workItemRepository,
                        ProjectSecurityService projectSecurityService,
                        WorkflowDefinitionResolver resolver,
                        NotificationDispatcher notificationDispatcher) {
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional(readOnly = true)
    public List<Asset> listAssets(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        return assetRepository.findAllByWorkItemId(workItemId);
    }

    @Transactional
    public Asset createAsset(String projectId, String workItemId, AssetInput input, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        validateAssetType(projectId, workItem, input.type());

        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType(input.type());
        asset.setLabel(input.label());
        asset.setKind(input.kind());
        asset.setRef(input.ref());
        asset.setDone(Boolean.TRUE.equals(input.done()));
        assetRepository.save(asset);

        notificationDispatcher.dispatch(NotificationEvent.of(EventType.ASSET_ADDED, projectId,
                Map.of("workItemId", workItem.getId(), "workItemTitle", workItem.getTitle(), "assetType", asset.getType())));
        return asset;
    }

    @Transactional
    public Asset patchAsset(String projectId, String workItemId, String assetId,
                            AssetPatch patch, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        if (patch.label() != null) {
            asset.setLabel(patch.label());
        }
        if (patch.ref() != null) {
            asset.setRef(patch.ref());
        }
        if (patch.done() != null) {
            asset.setDone(patch.done());
        }
        assetRepository.save(asset);
        return asset;
    }

    @Transactional
    public void deleteAsset(String projectId, String workItemId, String assetId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        assetRepository.delete(asset);
    }

    /**
     * System path: record a {@code github_pr} Asset when a PR merges (called from
     * {@code WorkItemService.completeFromPullRequest}). Thin engineering-specific wrapper over the generic
     * {@link #recordAsset} so non-GitHub lifecycles can auto-record their own asset types the same way.
     */
    @Transactional
    public void recordPullRequestAsset(WorkItem workItem, String pullRequestUrl) {
        recordAsset(workItem, "github_pr", pullRequestUrl, "Pull Request", "link");
    }

    /**
     * System path: record an arbitrary produced Asset on a Work Item (no caller/membership check). Idempotent
     * on {@code (workItem, type, ref)}. Domain-agnostic — the type is passed in, not hardcoded — so any lifecycle
     * (marketing, docs, …) can auto-record its outputs, not just GitHub PRs.
     */
    @Transactional
    public void recordAsset(WorkItem workItem, String type, String ref, String label, String kind) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        if (assetRepository.existsByWorkItemIdAndTypeAndRef(workItem.getId(), type, ref)) {
            return;
        }
        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType(type);
        asset.setLabel(label);
        asset.setKind(kind);
        asset.setRef(ref);
        asset.setDone(true);
        assetRepository.save(asset);
    }

    private void validateAssetType(String projectId, WorkItem workItem, String type) {
        String slug = workItem.getWorkflow() != null ? workItem.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug, workItem.getWorkflowVersion());
        List<String> allowed = statechart.assetTypes();
        if (!allowed.isEmpty() && !allowed.contains(type)) {
            throw new BusinessException("Asset type '" + type + "' is not allowed by workflow " + slug);
        }
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        return workItemRepository.findById(workItemId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }

    private Asset findAssetInWorkItem(String workItemId, String assetId) {
        return assetRepository.findByIdAndWorkItemId(assetId, workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Asset not found in Work Item"));
    }
}
