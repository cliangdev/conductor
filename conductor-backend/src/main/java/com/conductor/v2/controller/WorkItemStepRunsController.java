package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkItemStepRun;
import com.conductor.generated.v2.api.WorkItemStepRunsApi;
import com.conductor.generated.v2.model.CreateStepRunRequest;
import com.conductor.generated.v2.model.StepRunBeforeAfter;
import com.conductor.generated.v2.model.StepRunFlag;
import com.conductor.generated.v2.model.StepRunProduced;
import com.conductor.generated.v2.model.StepRunResponse;
import com.conductor.service.WorkItemStepRunService;
import com.conductor.service.view.StepRunInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 step-run sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/step-runs}). All business logic lives in the
 * shared {@link WorkItemStepRunService}, which persists/returns WorkItemStepRun entities. This controller owns the translation
 * between the v2 request/response DTOs and the entity — including serializing the structured
 * {@code produced}/{@code beforeAfter}/{@code flags} to/from the JSONB columns via {@link ObjectMapper}.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 *
 * <p>No {@code @Transactional} here: the mapping reads only the WorkItemStepRun's own columns and its parent id, so no
 * lazy association is loaded during mapping (open-in-view is off).
 */
@RestController
public class WorkItemStepRunsController implements WorkItemStepRunsApi {

    private final WorkItemStepRunService stepRunService;
    private final ObjectMapper objectMapper;

    public WorkItemStepRunsController(WorkItemStepRunService stepRunService, ObjectMapper objectMapper) {
        this.stepRunService = stepRunService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<StepRunResponse>> listWorkItemStepRuns(String projectId, String workItemId) {
        List<StepRunResponse> body = stepRunService.listStepRuns(projectId, workItemId, currentUser()).stream()
                .map(this::toV2)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<StepRunResponse> createWorkItemStepRun(String projectId, String workItemId,
                                                                CreateStepRunRequest request) {
        StepRunInput input = new StepRunInput(
                request.getWorkflow(),
                request.getFromStatus(),
                request.getToStatus(),
                request.getStepKind().getValue(),
                request.getSkill(),
                request.getStatus().getValue(),
                request.getInputBrief(),
                request.getReportedBy(),
                request.getStartedAt(),
                request.getFinishedAt(),
                toJson(request.getProduced()),
                toJson(request.getBeforeAfter()),
                toJson(request.getFlags()));
        WorkItemStepRun created = stepRunService.createStepRun(projectId, workItemId, input, currentUser());
        return ResponseEntity.status(201).body(toV2(created));
    }

    private JsonNode toJson(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private StepRunResponse toV2(WorkItemStepRun stepRun) {
        StepRunResponse v2 = new StepRunResponse(
                stepRun.getId(),
                stepRun.getWorkItem().getId(),
                stepRun.getStatus(),
                stepRun.getInputBrief(),
                stepRun.getReportedBy(),
                stepRun.getCreatedAt())
                .workflow(stepRun.getWorkflow())
                .fromStatus(stepRun.getFromStatus())
                .toStatus(stepRun.getToStatus())
                .stepKind(stepRun.getStepKind())
                .skill(stepRun.getSkill())
                .startedAt(stepRun.getStartedAt())
                .finishedAt(stepRun.getFinishedAt());
        if (stepRun.getProduced() != null) {
            v2.produced(objectMapper.convertValue(stepRun.getProduced(),
                    new TypeReference<List<StepRunProduced>>() {}));
        }
        if (stepRun.getBeforeAfter() != null) {
            v2.beforeAfter(objectMapper.convertValue(stepRun.getBeforeAfter(), StepRunBeforeAfter.class));
        }
        if (stepRun.getFlags() != null) {
            v2.flags(objectMapper.convertValue(stepRun.getFlags(),
                    new TypeReference<List<StepRunFlag>>() {}));
        }
        return v2;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
