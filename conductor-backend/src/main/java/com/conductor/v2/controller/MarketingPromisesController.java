package com.conductor.v2.controller;

import com.conductor.generated.v2.api.MarketingPromisesApi;
import com.conductor.generated.v2.model.MessageTag;
import com.conductor.generated.v2.model.MessageTagListResponse;
import com.conductor.generated.v2.model.MessageTagRequest;
import com.conductor.generated.v2.model.PromiseIntentShare;
import com.conductor.generated.v2.model.PromiseLedgerResponse;
import com.conductor.generated.v2.model.PromiseRate;
import com.conductor.marketing.promises.MarketingMessageTag;
import com.conductor.marketing.promises.MessageTagService;
import com.conductor.marketing.promises.MessageTagService.TagInput;
import com.conductor.marketing.promises.PromiseLedgerService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The promise ledger and the message tags behind it. Any project member reads; writing a tag needs ADMIN or
 * CREATOR. The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingPromisesController implements MarketingPromisesApi {

    private final PromiseLedgerService ledger;
    private final MessageTagService tags;
    private final ProjectSecurityService projectSecurityService;

    public MarketingPromisesController(PromiseLedgerService ledger, MessageTagService tags,
                                       ProjectSecurityService projectSecurityService) {
        this.ledger = ledger;
        this.tags = tags;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<PromiseLedgerResponse> getMarketingPromises(String projectId, LocalDate from, LocalDate to) {
        projectSecurityService.requireProjectAccess(projectId);
        var result = ledger.promises(projectId, from, to);
        return ResponseEntity.ok(new PromiseLedgerResponse(result.from(), result.to(),
                result.promises().stream().map(MarketingPromisesController::toPromise).toList()));
    }

    @Override
    public ResponseEntity<MessageTagListResponse> listMarketingMessageTags(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(new MessageTagListResponse(
                tags.list(projectId).stream().map(MarketingPromisesController::toTag).toList()));
    }

    @Override
    public ResponseEntity<MessageTag> setMarketingMessageTag(String projectId, MessageTagRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var saved = tags.tag(projectId, new TagInput(request.getMessage(), request.getTargetKind(),
                request.getTargetRef(), request.getLabel()), actor.userId());
        return ResponseEntity.ok(toTag(saved));
    }

    @Override
    public ResponseEntity<Void> deleteMarketingMessageTag(String projectId, String targetKind, String targetRef) {
        projectSecurityService.requireProjectEditor(projectId);
        tags.untag(projectId, targetKind, targetRef);
        return ResponseEntity.noContent().build();
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static MessageTag toTag(MarketingMessageTag t) {
        return new MessageTag(t.getId(), t.getMessage(), t.getTargetKind(), t.getTargetRef(), t.getCreatedAt())
                .label(t.getLabel());
    }

    private static com.conductor.generated.v2.model.Promise toPromise(PromiseLedgerService.Promise p) {
        List<PromiseIntentShare> mix = p.intentMix() == null ? null : p.intentMix().stream()
                .map(s -> new PromiseIntentShare(s.pick(), s.count(), s.share())).toList();
        return new com.conductor.generated.v2.model.Promise(p.message(), p.targets(), p.smallN())
                .installs(p.installs()).installsNote(p.installsNote()).intentMix(mix).intentN(p.intentN())
                .trialRate(toRate(p.trialRate())).d7(toRate(p.d7())).outcomesNote(p.outcomesNote());
    }

    private static PromiseRate toRate(PromiseLedgerService.Rate r) {
        return r == null ? null : new PromiseRate(r.value(), r.n(), r.low(), r.high(), r.smallN());
    }
}
