package com.conductor.legacy;

import com.conductor.entity.User;
import com.conductor.generated.api.StepRunsApi;
import com.conductor.generated.model.CreateStepRunRequest;
import com.conductor.generated.model.StepRunResponse;
import com.conductor.service.StepRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StepRunController implements StepRunsApi {

    private final StepRunService stepRunService;

    public StepRunController(StepRunService stepRunService) {
        this.stepRunService = stepRunService;
    }

    @Override
    public ResponseEntity<List<StepRunResponse>> listStepRuns(String projectId, String issueId) {
        return ResponseEntity.ok(stepRunService.listStepRuns(projectId, issueId, currentUser()));
    }

    @Override
    public ResponseEntity<StepRunResponse> createStepRun(String projectId, String issueId, CreateStepRunRequest createStepRunRequest) {
        StepRunResponse response = stepRunService.createStepRun(projectId, issueId, createStepRunRequest, currentUser());
        return ResponseEntity.status(201).body(response);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
