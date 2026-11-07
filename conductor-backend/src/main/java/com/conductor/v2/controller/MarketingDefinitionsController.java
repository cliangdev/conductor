package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingDefinitionsApi;
import com.conductor.generated.v2.model.MarketingDefinitionEntry;
import com.conductor.generated.v2.model.MarketingDefinitionsResponse;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * What every marketing metric counts: one definition per visible metric of every channel, the workspace's in-app
 * metrics included. Any project member reads. The {@code /api/v2} prefix is applied structurally by
 * {@code ApiPathConfig}, so the mapping is bare.
 */
@RestController
public class MarketingDefinitionsController implements MarketingDefinitionsApi {

    private final MarketingOverviewQueryService queryService;
    private final ProjectSecurityService projectSecurityService;

    public MarketingDefinitionsController(MarketingOverviewQueryService queryService,
                                          ProjectSecurityService projectSecurityService) {
        this.queryService = queryService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingDefinitionsResponse> getMarketingDefinitions(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new MarketingDefinitionsResponse(
                queryService.definitions(projectId).stream().map(MarketingDefinitionsController::toEntry).toList()));
    }

    private static MarketingDefinitionEntry toEntry(MarketingOverviewQueryService.MetricDefinitionView d) {
        return new MarketingDefinitionEntry(d.metricRef(), d.channel(), d.key(), d.label(),
                MarketingDefinitionEntry.UnitEnum.valueOf(d.unit().name()), d.aggregation(),
                MarketingOverviewController.toDefinition(d.definition()));
    }
}
