package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.api.MarketingAiVisibilityApi;
import com.conductor.generated.v2.model.AiBrandTerms;
import com.conductor.generated.v2.model.AiQuestion;
import com.conductor.generated.v2.model.AiQuestionList;
import com.conductor.generated.v2.model.AiVisibility;
import com.conductor.generated.v2.model.AiVisibilityAssistant;
import com.conductor.generated.v2.model.AiVisibilityFailure;
import com.conductor.generated.v2.model.AiVisibilityQuestion;
import com.conductor.generated.v2.model.AiVisibilityResult;
import com.conductor.generated.v2.model.AiVisibilityRunResult;
import com.conductor.generated.v2.model.AiVisibilitySkipped;
import com.conductor.generated.v2.model.AiVisibilityTrendPoint;
import com.conductor.generated.v2.model.SetAiBrandTermsRequest;
import com.conductor.generated.v2.model.SetAiQuestionsRequest;
import com.conductor.marketing.aivisibility.AiVisibilityService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Weekly checks of whether AI assistants name the brand. Any project member reads; the question set and brand terms
 * need ADMIN or CREATOR; running the checks now needs ADMIN (it spends the workspace's provider keys). The
 * {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingAiVisibilityController implements MarketingAiVisibilityApi {

    private final AiVisibilityService service;
    private final ProjectSecurityService projectSecurityService;

    public MarketingAiVisibilityController(AiVisibilityService service,
                                           ProjectSecurityService projectSecurityService) {
        this.service = service;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<AiVisibility> getAiVisibility(String projectId, Integer weeks) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toView(service.view(projectId,
                weeks == null ? AiVisibilityService.DEFAULT_WEEKS : weeks)));
    }

    @Override
    public ResponseEntity<AiQuestionList> setAiVisibilityQuestions(String projectId, SetAiQuestionsRequest request) {
        var actor = projectSecurityService.requireProjectEditor(projectId);
        var rows = service.setQuestions(projectId, request.getQuestions(), actor.userId());
        return ResponseEntity.ok(new AiQuestionList(
                rows.stream().map(q -> new AiQuestion(q.id(), q.question(), q.active())).toList()));
    }

    @Override
    public ResponseEntity<AiBrandTerms> setAiVisibilityBrandTerms(String projectId, SetAiBrandTermsRequest request) {
        projectSecurityService.requireProjectEditor(projectId);
        return ResponseEntity.ok(new AiBrandTerms(service.setBrandTerms(projectId, request.getBrandTerms())));
    }

    @Override
    public ResponseEntity<AiVisibilityRunResult> runAiVisibilityCheck(String projectId) {
        requireProjectAdmin(projectId);
        return ResponseEntity.ok(toRunResult(service.runChecks(projectId)));
    }

    /** ADMIN only, and only a person: a run spends the workspace's provider keys, so no machine credential may start one. */
    private void requireProjectAdmin(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user) || !projectSecurityService.isProjectAdmin(projectId, user.getId())) {
            throw new ForbiddenException("Requires the Admin role");
        }
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static AiVisibility toView(AiVisibilityService.View v) {
        return new AiVisibility(v.brandTerms(),
                v.questions().stream().map(MarketingAiVisibilityController::toQuestion).toList(),
                v.skipped().stream().map(MarketingAiVisibilityController::toSkipped).toList());
    }

    private static AiVisibilityQuestion toQuestion(AiVisibilityService.QuestionView q) {
        List<AiVisibilityAssistant> assistants = q.assistants().stream().map(a -> new AiVisibilityAssistant(
                a.assistant(), toResult(a.latest()),
                a.trend().stream().map(t -> new AiVisibilityTrendPoint(t.weekStart(), t.named())).toList())).toList();
        return new AiVisibilityQuestion(q.id(), q.question(), q.active(), assistants);
    }

    private static AiVisibilityResult toResult(AiVisibilityService.Result r) {
        return new AiVisibilityResult(r.weekStart(), r.named()).rankInAnswer(r.rankInAnswer()).excerpt(r.excerpt())
                .model(r.model());
    }

    private static AiVisibilitySkipped toSkipped(AiVisibilityService.Skipped s) {
        return new AiVisibilitySkipped(s.assistant(), s.reason());
    }

    private static AiVisibilityRunResult toRunResult(AiVisibilityService.RunResult r) {
        return new AiVisibilityRunResult(r.weekStart(), r.recorded(), r.alreadyRecorded(),
                r.skipped().stream().map(MarketingAiVisibilityController::toSkipped).toList(),
                r.errors().stream().map(e -> new AiVisibilityFailure(e.assistant(), e.reason()).question(e.question()))
                        .toList());
    }
}
