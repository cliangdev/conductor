package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemMetricApi;
import com.conductor.generated.v2.model.MetricObservation;
import com.conductor.generated.v2.model.OutcomeMetricResponse;
import com.conductor.generated.v2.model.RecordMetricObservationRequest;
import com.conductor.service.OutcomeMetricService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Outcome Metric sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/metric}). Successor to the legacy v1
 * {@code OutcomeMetricController}; additive, no v1 behavior change. All business logic lives in the shared
 * {@link OutcomeMetricService}, which returns a fully-assembled DTO — this controller only translates the v1
 * service DTO to its v2 copy (identical shape).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 *
 * <p>No {@code @Transactional} here: {@link OutcomeMetricService} returns a DTO assembled inside its own
 * transaction, so no lazy associations are touched during this controller's mapping (open-in-view is off).
 */
@RestController
public class WorkItemMetricController implements WorkItemMetricApi {

    private final OutcomeMetricService outcomeMetricService;

    public WorkItemMetricController(OutcomeMetricService outcomeMetricService) {
        this.outcomeMetricService = outcomeMetricService;
    }

    @Override
    public ResponseEntity<OutcomeMetricResponse> getWorkItemMetric(String projectId, String workItemId) {
        return ResponseEntity.ok(toV2(outcomeMetricService.getMetric(projectId, workItemId, currentUser())));
    }

    @Override
    public ResponseEntity<OutcomeMetricResponse> recordWorkItemMetric(String projectId, String workItemId,
                                                                      RecordMetricObservationRequest request) {
        com.conductor.generated.model.RecordMetricObservationRequest v1 =
                new com.conductor.generated.model.RecordMetricObservationRequest(request.getValue());
        v1.setObservedAt(request.getObservedAt());
        v1.setNote(request.getNote());
        return ResponseEntity.ok(toV2(outcomeMetricService.record(projectId, workItemId, v1, currentUser())));
    }

    private static OutcomeMetricResponse toV2(com.conductor.generated.model.OutcomeMetricResponse v1) {
        List<MetricObservation> observations = v1.getObservations() == null ? List.of()
                : v1.getObservations().stream()
                        .map(o -> new MetricObservation(o.getValue(), o.getObservedAt()).note(o.getNote()))
                        .toList();
        return new OutcomeMetricResponse(observations)
                .name(v1.getName())
                .unit(v1.getUnit())
                .direction(v1.getDirection());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
