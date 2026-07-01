package com.conductor.legacy;

import com.conductor.entity.User;
import com.conductor.generated.api.MetricsApi;
import com.conductor.generated.model.OutcomeMetricResponse;
import com.conductor.generated.model.RecordMetricObservationRequest;
import com.conductor.service.OutcomeMetricService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OutcomeMetricController implements MetricsApi {

    private final OutcomeMetricService outcomeMetricService;

    public OutcomeMetricController(OutcomeMetricService outcomeMetricService) {
        this.outcomeMetricService = outcomeMetricService;
    }

    @Override
    public ResponseEntity<OutcomeMetricResponse> getOutcomeMetric(String projectId, String issueId) {
        return ResponseEntity.ok(outcomeMetricService.getMetric(projectId, issueId, currentUser()));
    }

    @Override
    public ResponseEntity<OutcomeMetricResponse> recordOutcomeMetric(String projectId, String issueId, RecordMetricObservationRequest recordMetricObservationRequest) {
        return ResponseEntity.ok(outcomeMetricService.record(projectId, issueId, recordMetricObservationRequest, currentUser()));
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
