package com.conductor.v2.controller;

import com.conductor.entity.Milestone;
import com.conductor.generated.v2.api.MilestonesApi;
import com.conductor.generated.v2.model.CreateMilestoneRequest;
import com.conductor.generated.v2.model.MilestoneProgress;
import com.conductor.generated.v2.model.MilestoneResponse;
import com.conductor.generated.v2.model.PatchMilestoneRequest;
import com.conductor.service.MilestoneService;
import com.conductor.service.MilestoneService.MilestoneView;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Milestones ({@code /api/v2/projects/{projectId}/milestones}). Any project member or project credential can
 * read; writing needs an ADMIN or CREATOR (or a machine credential, which acts for the project). Business
 * logic lives in {@link MilestoneService}.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}, so this class maps at bare
 * paths via the generated interface.
 */
@RestController
public class MilestoneController implements MilestonesApi {

    private final MilestoneService milestoneService;
    private final ProjectSecurityService projectSecurityService;

    public MilestoneController(MilestoneService milestoneService, ProjectSecurityService projectSecurityService) {
        this.milestoneService = milestoneService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<List<MilestoneResponse>> listMilestones(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(milestoneService.list(projectId).stream().map(MilestoneController::toResponse).toList());
    }

    @Override
    @Transactional
    public ResponseEntity<MilestoneResponse> createMilestone(String projectId, CreateMilestoneRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        MilestoneView created = milestoneService.create(projectId, request.getName(), request.getDescription(),
                request.getTargetOn(),
                request.getState() == null ? null : request.getState().getValue(), request.getSortOrder());
        return ResponseEntity.status(201).body(toResponse(created));
    }

    @Override
    @Transactional
    public ResponseEntity<MilestoneResponse> patchMilestone(String projectId, String milestoneId,
                                                            PatchMilestoneRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        MilestoneView updated = milestoneService.update(projectId, milestoneId, request.getName(),
                request.getDescription(), request.getTargetOn(),
                request.getState() == null ? null : request.getState().getValue(), request.getSortOrder());
        return ResponseEntity.ok(toResponse(updated));
    }

    @Override
    @Transactional
    public ResponseEntity<Void> deleteMilestone(String projectId, String milestoneId) {
        projectSecurityService.requireProjectEditor(projectId);
        milestoneService.delete(projectId, milestoneId);
        return ResponseEntity.noContent().build();
    }

    static MilestoneResponse toResponse(MilestoneView view) {
        Milestone m = view.milestone();
        MilestoneService.MilestoneProgress p = view.progress();
        return new MilestoneResponse(
                m.getId(),
                m.getProject().getId(),
                m.getName(),
                MilestoneResponse.StateEnum.fromValue(m.getState()),
                m.getSortOrder(),
                new MilestoneProgress(p.topLevelTotal(), p.topLevelDone(), p.leafTotal(), p.leafDone()),
                m.getCreatedAt(),
                m.getUpdatedAt())
                .description(m.getDescription())
                .targetOn(m.getTargetOn());
    }
}
