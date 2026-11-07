package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingChangesApi;
import com.conductor.generated.v2.model.MarketingChangeImportItem;
import com.conductor.generated.v2.model.MarketingChangeImportNote;
import com.conductor.generated.v2.model.MarketingChangeRecord;
import com.conductor.generated.v2.model.MarketingChangesImportRequest;
import com.conductor.generated.v2.model.MarketingChangesImportResponse;
import com.conductor.marketing.changes.MarketingChangeImportService;
import com.conductor.marketing.changes.MarketingChangeImportService.ChangeRecord;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Imports an outside marketing change log into the MARKETING_CHANGES workflow. ADMIN or CREATOR only. Not
 * {@code @Transactional}: the service commits each record on its own so one refused record never undoes the
 * others. The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingChangesController implements MarketingChangesApi {

    private final MarketingChangeImportService importService;
    private final ProjectSecurityService projectSecurityService;

    public MarketingChangesController(MarketingChangeImportService importService,
                                      ProjectSecurityService projectSecurityService) {
        this.importService = importService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingChangesImportResponse> importMarketingChanges(String projectId,
                                                                               MarketingChangesImportRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        List<ChangeRecord> records = request.getRecords() == null ? List.of()
                : request.getRecords().stream().map(MarketingChangesController::toRecord).toList();
        MarketingChangeImportService.Result result = importService.importRecords(projectId, request.getSource(),
                records, actor);
        return ResponseEntity.ok(new MarketingChangesImportResponse(result.created(), result.alreadyImported(),
                result.skipped().stream().map(n -> new MarketingChangeImportNote(n.id(), n.reason())).toList(),
                result.refused().stream().map(n -> new MarketingChangeImportNote(n.id(), n.reason())).toList(),
                result.items().stream().map(i -> new MarketingChangeImportItem(i.id(), i.displayId(), i.recordId(),
                        i.status())).toList()));
    }

    private static ChangeRecord toRecord(MarketingChangeRecord r) {
        return new ChangeRecord(r.getId(), r.getDate(), r.getType(), r.getTitle(), r.getStatus(), r.getChanged(),
                r.getEvidence(), r.getHypothesis(), r.getBaseline(), r.getControl(), r.getLiveSince(),
                r.getReviewOn(), r.getOutcome(), r.getVerdict(), r.getLesson(), r.getStopRules(),
                r.getStopRulesNote());
    }
}
