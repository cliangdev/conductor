package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemForecastApi;
import com.conductor.generated.v2.model.WorkItemForecast;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.ThroughputForecastService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v2/projects/{projectId}/work-items/{workItemId}/forecast}: any project member or project
 * credential reads. The maths is in {@link ThroughputForecastService}.
 */
@RestController
public class WorkItemForecastController implements WorkItemForecastApi {

    private final ThroughputForecastService forecastService;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemForecastController(ThroughputForecastService forecastService,
                                      ProjectSecurityService projectSecurityService) {
        this.forecastService = forecastService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<WorkItemForecast> getWorkItemForecast(String projectId, String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(DeliveryMappers.forecast(forecastService.forecast(projectId, workItemId)));
    }
}
