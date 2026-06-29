package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemStepRunsApi;
import com.conductor.generated.v2.model.CreateStepRunRequest;
import com.conductor.generated.v2.model.StepRunBeforeAfter;
import com.conductor.generated.v2.model.StepRunFlag;
import com.conductor.generated.v2.model.StepRunProduced;
import com.conductor.generated.v2.model.StepRunResponse;
import com.conductor.service.StepRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 step-run sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/step-runs}). Successor to the legacy v1
 * {@code StepRunController}; additive, no v1 behavior change. All business logic lives in the shared
 * {@link StepRunService}, which returns fully-assembled DTOs — this controller only translates the v1
 * request/response DTOs to their v2 copies (the only shape difference is {@code issueId} → {@code workItemId}).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 *
 * <p>No {@code @Transactional} here: {@link StepRunService} returns DTOs assembled inside its own
 * transaction, so no lazy associations are touched during this controller's mapping (open-in-view is off).
 */
@RestController
public class WorkItemStepRunsController implements WorkItemStepRunsApi {

    private final StepRunService stepRunService;

    public WorkItemStepRunsController(StepRunService stepRunService) {
        this.stepRunService = stepRunService;
    }

    @Override
    public ResponseEntity<List<StepRunResponse>> listWorkItemStepRuns(String projectId, String workItemId) {
        List<StepRunResponse> body = stepRunService.listStepRuns(projectId, workItemId, currentUser()).stream()
                .map(WorkItemStepRunsController::toV2)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<StepRunResponse> createWorkItemStepRun(String projectId, String workItemId,
                                                                CreateStepRunRequest request) {
        com.conductor.generated.model.StepRunResponse created =
                stepRunService.createStepRun(projectId, workItemId, toV1(request), currentUser());
        return ResponseEntity.status(201).body(toV2(created));
    }

    private static com.conductor.generated.model.CreateStepRunRequest toV1(CreateStepRunRequest v2) {
        com.conductor.generated.model.CreateStepRunRequest v1 =
                new com.conductor.generated.model.CreateStepRunRequest(
                        com.conductor.generated.model.CreateStepRunRequest.StepKindEnum.fromValue(
                                v2.getStepKind().getValue()),
                        com.conductor.generated.model.CreateStepRunRequest.StatusEnum.fromValue(
                                v2.getStatus().getValue()),
                        v2.getInputBrief(),
                        v2.getReportedBy());
        v1.setWorkflow(v2.getWorkflow());
        v1.setFromStatus(v2.getFromStatus());
        v1.setToStatus(v2.getToStatus());
        v1.setSkill(v2.getSkill());
        v1.setStartedAt(v2.getStartedAt());
        v1.setFinishedAt(v2.getFinishedAt());
        if (v2.getProduced() != null) {
            v1.setProduced(v2.getProduced().stream()
                    .map(WorkItemStepRunsController::producedToV1)
                    .toList());
        }
        v1.setBeforeAfter(beforeAfterToV1(v2.getBeforeAfter()));
        if (v2.getFlags() != null) {
            v1.setFlags(v2.getFlags().stream()
                    .map(WorkItemStepRunsController::flagToV1)
                    .toList());
        }
        return v1;
    }

    private static com.conductor.generated.model.StepRunProduced producedToV1(StepRunProduced v2) {
        return new com.conductor.generated.model.StepRunProduced(
                com.conductor.generated.model.StepRunProduced.KindEnum.fromValue(v2.getKind().getValue()),
                v2.getRef());
    }

    private static com.conductor.generated.model.StepRunBeforeAfter beforeAfterToV1(StepRunBeforeAfter v2) {
        if (v2 == null) {
            return null;
        }
        return new com.conductor.generated.model.StepRunBeforeAfter(v2.getBefore(), v2.getAfter());
    }

    private static com.conductor.generated.model.StepRunFlag flagToV1(StepRunFlag v2) {
        return new com.conductor.generated.model.StepRunFlag(
                com.conductor.generated.model.StepRunFlag.LevelEnum.fromValue(v2.getLevel().getValue()),
                v2.getMessage());
    }

    private static StepRunResponse toV2(com.conductor.generated.model.StepRunResponse v1) {
        StepRunResponse v2 = new StepRunResponse(
                v1.getId(),
                v1.getIssueId(),
                v1.getStatus(),
                v1.getInputBrief(),
                v1.getReportedBy(),
                v1.getCreatedAt())
                .workflow(v1.getWorkflow())
                .fromStatus(v1.getFromStatus())
                .toStatus(v1.getToStatus())
                .stepKind(v1.getStepKind())
                .skill(v1.getSkill())
                .startedAt(v1.getStartedAt())
                .finishedAt(v1.getFinishedAt())
                .beforeAfter(beforeAfterToV2(v1.getBeforeAfter()));
        if (v1.getProduced() != null) {
            v2.produced(v1.getProduced().stream()
                    .map(WorkItemStepRunsController::producedToV2)
                    .toList());
        }
        if (v1.getFlags() != null) {
            v2.flags(v1.getFlags().stream()
                    .map(WorkItemStepRunsController::flagToV2)
                    .toList());
        }
        return v2;
    }

    private static StepRunProduced producedToV2(com.conductor.generated.model.StepRunProduced v1) {
        return new StepRunProduced(
                StepRunProduced.KindEnum.fromValue(v1.getKind().getValue()),
                v1.getRef());
    }

    private static StepRunBeforeAfter beforeAfterToV2(com.conductor.generated.model.StepRunBeforeAfter v1) {
        if (v1 == null) {
            return null;
        }
        return new StepRunBeforeAfter(v1.getBefore(), v1.getAfter());
    }

    private static StepRunFlag flagToV2(com.conductor.generated.model.StepRunFlag v1) {
        return new StepRunFlag(
                StepRunFlag.LevelEnum.fromValue(v1.getLevel().getValue()),
                v1.getMessage());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
