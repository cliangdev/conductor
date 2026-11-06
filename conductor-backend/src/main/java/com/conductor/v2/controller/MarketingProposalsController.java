package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.api.MarketingProposalsApi;
import com.conductor.generated.v2.model.MarketingProposalRequest;
import com.conductor.generated.v2.model.MarketingProposalResponse;
import com.conductor.generated.v2.model.MarketingProposalSignal;
import com.conductor.generated.v2.model.WeeklyGrowthInstallResponse;
import com.conductor.marketing.proposals.MarketingProposalService;
import com.conductor.marketing.proposals.MarketingProposalService.Proposal;
import com.conductor.marketing.proposals.MarketingProposalService.Signal;
import com.conductor.marketing.proposals.WeeklyGrowthWorkflowService;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Weekly growth proposals and the workflow that makes them. Proposing needs ADMIN or CREATOR; a project key or a run
 * token passes too, which is how the workflow's own agent proposes. Installing the workflow needs ADMIN and a person:
 * an agent cannot install workflows. Neither is {@code @Transactional}: each service commits its own work. The
 * {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class MarketingProposalsController implements MarketingProposalsApi {

    private final MarketingProposalService proposals;
    private final WeeklyGrowthWorkflowService installer;
    private final ProjectSecurityService projectSecurityService;

    public MarketingProposalsController(MarketingProposalService proposals, WeeklyGrowthWorkflowService installer,
                                        ProjectSecurityService projectSecurityService) {
        this.proposals = proposals;
        this.installer = installer;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<MarketingProposalResponse> proposeMarketingChange(String projectId,
                                                                           MarketingProposalRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        List<Signal> signals = request.getSignals() == null ? null
                : request.getSignals().stream().map(MarketingProposalsController::toSignal).toList();
        MarketingProposalService.Result result = proposals.propose(projectId,
                new Proposal(request.getTitle(), request.getType(), request.getHypothesis(), request.getReviewOn(),
                        signals, request.getRunId()), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(new MarketingProposalResponse(result.workItemId(),
                result.displayId(), result.proposalsThisWeek(), result.weeklyLimit()));
    }

    @Override
    public ResponseEntity<WeeklyGrowthInstallResponse> installWeeklyGrowthWorkflow(String projectId) {
        User admin = requireProjectAdmin(projectId);
        WeeklyGrowthWorkflowService.Installed installed = installer.install(projectId, admin.getId());
        return ResponseEntity.status(installed.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new WeeklyGrowthInstallResponse(installed.workflowId(), installed.name(), installed.enabled(),
                        installed.created()));
    }

    /** ADMIN only, and only a person: a project key or run token has no user, so an agent cannot install a workflow. */
    private User requireProjectAdmin(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user) || !projectSecurityService.isProjectAdmin(projectId, user.getId())) {
            throw new ForbiddenException("Requires the Admin role");
        }
        return user;
    }

    private static Signal toSignal(MarketingProposalSignal s) {
        return s == null ? null : new Signal(s.getMetricRef(), s.getWindow(), s.getValue(), s.getNote());
    }
}
