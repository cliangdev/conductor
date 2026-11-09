package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingVerificationApi;
import com.conductor.generated.v2.model.MarketingPlatformReadEntry;
import com.conductor.generated.v2.model.MarketingPlatformReadList;
import com.conductor.generated.v2.model.MarketingPlatformReadRequest;
import com.conductor.generated.v2.model.MarketingPlatformReadRetractRequest;
import com.conductor.generated.v2.model.MarketingVerificationLedger;
import com.conductor.generated.v2.model.VerificationBackend;
import com.conductor.generated.v2.model.VerificationPlatformRead;
import com.conductor.generated.v2.model.VerificationRow;
import com.conductor.generated.v2.model.VerificationSummary;
import com.conductor.generated.v2.model.VerificationWindow;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketing verification ledger and the direct platform reads behind it. Any project member reads; recording a
 * read or retracting one needs ADMIN or CREATOR. There is deliberately no update or delete: reads are never
 * deleted; a wrong read can be retracted, after which it no longer counts (the previous read, if any, applies) but
 * stays in the history with its retraction fields. The
 * {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}, so the mappings are bare.
 */
@RestController
public class MarketingVerificationController implements MarketingVerificationApi {

    private final VerificationLedgerService ledgerService;
    private final ProjectSecurityService projectSecurityService;

    public MarketingVerificationController(VerificationLedgerService ledgerService,
                                           ProjectSecurityService projectSecurityService) {
        this.ledgerService = ledgerService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingVerificationLedger> getMarketingVerification(String projectId, String window) {
        projectSecurityService.requireProjectAccess(projectId);
        VerificationLedgerService.Ledger ledger = ledgerService.ledger(projectId, window);
        VerificationLedgerService.Summary s = ledger.summary();
        return ResponseEntity.ok(new MarketingVerificationLedger(
                new VerificationWindow(ledger.from()).to(ledger.to()),
                ledger.rows().stream().map(MarketingVerificationController::toRow).toList(),
                new VerificationSummary(s.both(), s.connector(), s.backend(), s.platform(), s.unchecked(),
                        s.mismatched(), s.staleReads())));
    }

    @Override
    public ResponseEntity<VerificationRow> recordMarketingPlatformRead(String projectId,
                                                                       MarketingPlatformReadRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        VerificationLedgerService.RecordedRow recorded = ledgerService.record(projectId, request.getChannel(),
                request.getMetric(), request.getFrom(), request.getTo(), request.getValue(), request.getSourceName(),
                request.getNote(), actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toRow(recorded.row()));
    }

    @Override
    public ResponseEntity<MarketingPlatformReadEntry> retractMarketingPlatformRead(
            String projectId, String readId, MarketingPlatformReadRetractRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(toEntry(ledgerService.retract(projectId, readId, request.getReason(),
                actor.userId())));
    }

    @Override
    public ResponseEntity<MarketingPlatformReadList> listMarketingPlatformReads(String projectId, String channel,
                                                                                String metric, Integer limit) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new MarketingPlatformReadList(
                ledgerService.reads(projectId, channel, metric, limit).stream()
                        .map(MarketingVerificationController::toEntry).toList()));
    }

    // ---- mapping ------------------------------------------------------------------------------------

    static VerificationRow toRow(LedgerRow r) {
        return new VerificationRow(r.channel(), r.metric(), r.metricRef(), r.label(),
                VerificationRow.UnitEnum.valueOf(r.unit().name()), VerificationRow.VerdictEnum.valueOf(r.verdict().name()),
                r.mismatch(), r.readStale(), r.trustNote())
                .definition(MarketingOverviewController.toDefinition(r.definition()))
                .windowFrom(r.windowFrom())
                .windowTo(r.windowTo())
                .conductorValue(r.conductorValue())
                .backend(r.backend() == null ? null : toBackend(r.backend()))
                .platformRead(r.platformRead() == null ? null : toPlatformRead(r.platformRead()));
    }

    private static VerificationBackend toBackend(VerificationLedgerService.BackendFigure b) {
        return new VerificationBackend(b.value(), b.conductorValue(), b.daysCompared(), b.source(), b.from(), b.to(),
                b.matches());
    }

    private static VerificationPlatformRead toPlatformRead(VerificationLedgerService.PlatformReadFigure p) {
        return new VerificationPlatformRead(p.id(), p.value(), p.from(), p.to(), p.sourceName(), p.recordedAt())
                .note(p.note())
                .recordedBy(p.recordedBy())
                .conductorValueOverReadDays(p.conductorValueOverReadDays())
                .matches(p.matches());
    }

    private static MarketingPlatformReadEntry toEntry(VerificationLedgerService.ReadEntry e) {
        return new MarketingPlatformReadEntry(e.id(), e.channel(), e.metric(), e.from(), e.to(), e.value(),
                e.sourceName(), e.recordedAt())
                .note(e.note())
                .recordedBy(e.recordedBy())
                .conductorValue(e.conductorValue())
                .backendValue(e.backendValue())
                .verdict(e.verdict() == null ? null : MarketingPlatformReadEntry.VerdictEnum.valueOf(e.verdict()))
                .retractedAt(e.retractedAt())
                .retractedBy(e.retractedBy())
                .retractionReason(e.retractionReason());
    }
}
