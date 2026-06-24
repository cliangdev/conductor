package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.MemberRole;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.AvailableTransition;
import com.conductor.generated.model.AvailableTransitionsResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.conductor.workflow.lifecycle.WorkflowEngine;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns Work Item status transitions against the bound Workflow's {@link Statechart} (COND-18 E2). It is the
 * seam {@code IssueService} delegates transition validation to, and the source of the doer projection behind
 * {@code GET .../available-transitions}.
 *
 * <p>For an ENGINEERING-bound issue the resolved statechart reproduces today's exact {@code VALID_TRANSITIONS}
 * edges, so an illegal move throws the <em>identical</em> {@code BusinessException} message — no behavior
 * change (AC-P0-1.1). Issues with no explicit binding default to the built-in ENGINEERING workflow.
 */
@Service
public class WorkItemTransitionService {

    static final String DEFAULT_WORKFLOW = "ENGINEERING";

    private final IssueRepository issueRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkflowDefinitionResolver resolver;
    private final WorkflowEngine engine;

    public WorkItemTransitionService(IssueRepository issueRepository,
                                     ProjectSecurityService projectSecurityService,
                                     ProjectMemberRepository projectMemberRepository,
                                     WorkflowDefinitionResolver resolver,
                                     WorkflowEngine engine) {
        this.issueRepository = issueRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.resolver = resolver;
        this.engine = engine;
    }

    /**
     * Validate a status change against the issue's bound Workflow. Throws the same
     * {@code "Invalid status transition from X to Y"} {@link BusinessException} the legacy hardcoded map threw,
     * so existing behavior and tests are preserved.
     */
    public void validateTransition(String projectId, Issue issue, IssueStatus newStatus) {
        Statechart statechart = resolveFor(projectId, issue);
        if (!engine.canTransition(statechart, issue.getStatus().name(), newStatus.name())) {
            throw new BusinessException(
                    "Invalid status transition from " + issue.getStatus() + " to " + newStatus);
        }
    }

    /**
     * The doer projection: the valid next transitions for this actor from the issue's current status,
     * computed from the bound Workflow definition. REVIEWERs cannot change status, so they see none.
     */
    @Transactional(readOnly = true)
    public AvailableTransitionsResponse availableTransitions(String projectId, String issueId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Issue not found");
        }
        Issue issue = issueRepository.findById(issueId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));

        Statechart statechart = resolveFor(projectId, issue);
        String currentStatus = issue.getStatus().name();

        List<AvailableTransition> transitions = new ArrayList<>();
        if (!isReviewer(projectId, caller.getId())) {
            for (StatechartTransition t : engine.availableTransitions(statechart, currentStatus)) {
                AvailableTransition at = new AvailableTransition(t.to(), t.label());
                at.setRequiresReview(t.requiresReview());
                transitions.add(at);
            }
        }

        AvailableTransitionsResponse response =
                new AvailableTransitionsResponse(statechart.slug(), currentStatus, transitions);
        response.setNoun(statechart.noun());
        return response;
    }

    private Statechart resolveFor(String projectId, Issue issue) {
        String slug = issue.getWorkflow() != null ? issue.getWorkflow() : DEFAULT_WORKFLOW;
        return resolver.resolveRequired(projectId, slug);
    }

    private boolean isReviewer(String projectId, String callerId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, callerId)
                .map(m -> m.getRole() == MemberRole.REVIEWER)
                .orElse(false);
    }
}
