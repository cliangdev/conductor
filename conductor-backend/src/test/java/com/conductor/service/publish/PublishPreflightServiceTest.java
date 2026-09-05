package com.conductor.service.publish;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.service.MediaTargetValidator;
import com.conductor.service.PostScheduleValidator;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.PublishConsentService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishPreflightServiceTest {

    private static final String PROJECT = "proj-1";

    private ProjectSecurityService security;
    private WorkItemRepository workItemRepository;
    private WorkflowDefinitionResolver resolver;
    private PublishGateEvaluator evaluator;
    private PostScheduleValidator postScheduleValidator;
    private PostPublishTargetRepository targetRepository;
    private PublishConsentService consentService;
    private WorkItemReviewerRepository reviewerRepository;
    private WorkItemWorkflowService workflowService;
    private PublishPreflightService service;
    private Statechart marketing;
    private Statechart engineering;
    private WorkItem post;
    private User caller;

    @BeforeEach
    void setUp() throws Exception {
        security = mock(ProjectSecurityService.class);
        workItemRepository = mock(WorkItemRepository.class);
        resolver = mock(WorkflowDefinitionResolver.class);
        evaluator = mock(PublishGateEvaluator.class);
        postScheduleValidator = mock(PostScheduleValidator.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        consentService = mock(PublishConsentService.class);
        reviewerRepository = mock(WorkItemReviewerRepository.class);
        workflowService = mock(WorkItemWorkflowService.class);
        PublishPlatformRegistry registry = new PublishPlatformRegistry();
        service = new PublishPreflightService(security, workItemRepository, resolver,
                new PublishingWorkflow(registry, resolver), evaluator, postScheduleValidator, targetRepository,
                consentService, reviewerRepository, workflowService);

        ObjectMapper mapper = new ObjectMapper();
        marketing = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")));
        engineering = Statechart.parse(mapper.readTree(getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")));

        Project project = new Project();
        project.setId(PROJECT);
        post = new WorkItem();
        post.setId("post-1");
        post.setProject(project);
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCurrentStatus("DRAFT");
        caller = new User();
        caller.setId("user-1");

        when(security.isProjectMember(PROJECT, "user-1")).thenReturn(true);
        when(workItemRepository.findById("post-1")).thenReturn(Optional.of(post));
        when(resolver.resolveRequired(PROJECT, "MARKETING", 1)).thenReturn(marketing);
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of());
        when(reviewerRepository.findAllByWorkItemId("post-1")).thenReturn(List.of());
        when(consentService.verdict(post)).thenReturn(PublishConsentService.Verdict.NOT_REQUIRED);
        when(evaluator.evaluate(post)).thenReturn(new PublishGateEvaluator.Evaluation(List.of(), List.of()));
        when(postScheduleValidator.earliestFireTime(anyList()))
                .thenReturn(OffsetDateTime.parse("2026-09-04T12:10:00Z"));
    }

    @Test
    void aNonMemberIsToldTheItemDoesNotExist() {
        assertThatThrownBy(() -> service.preflight(PROJECT, "post-1", user("stranger")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void anItemInAnotherProjectIsNotFound() {
        Project other = new Project();
        other.setId("proj-2");
        post.setProject(other);
        assertThatThrownBy(() -> service.preflight(PROJECT, "post-1", caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void aNonPublishingWorkflowHasNothingToSay() {
        post.setWorkflow("ENGINEERING");
        when(resolver.resolveRequired(PROJECT, "ENGINEERING", 1)).thenReturn(engineering);

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.publishing()).isFalse();
        assertThat(preflight.ready()).isTrue();
        assertThat(preflight.blockers()).isEmpty();
        assertThat(preflight.nextTransition()).isNull();
        assertThat(preflight.earliestFireTime()).isNull();
    }

    @Test
    void aDraftReportsBlockersAndTheReviewGateAsItsNextGateMove() {
        when(evaluator.evaluate(post)).thenReturn(new PublishGateEvaluator.Evaluation(
                List.of(PublishFinding.blocker(PostScheduleValidator.NO_FIRE_TIME, "no fire time is set")),
                List.of(PublishFinding.warning(MediaTargetValidator.MEDIA_ADVISORY, "a Short", "t-1"))));
        post.setCurrentStatus("IN_REVIEW");
        when(reviewerRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(new WorkItemReviewer()));
        when(workflowService.isReviewSatisfied(eq(PROJECT), eq(post), any())).thenReturn(false);

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.publishing()).isTrue();
        assertThat(preflight.ready()).isFalse();
        assertThat(preflight.blockers()).extracting(PublishFinding::code).containsExactly("NO_FIRE_TIME");
        assertThat(preflight.warnings()).extracting(PublishFinding::message).containsExactly("a Short");
        assertThat(preflight.nextTransition().to()).isEqualTo("APPROVED");
        assertThat(preflight.nextTransition().label()).isEqualTo("Approve");
        assertThat(preflight.nextTransition().requiresReview()).isTrue();
        assertThat(preflight.review().gated()).isTrue();
        assertThat(preflight.review().assignedReviewers()).isEqualTo(1);
        assertThat(preflight.review().satisfied()).isFalse();
        assertThat(preflight.review().reviewerRole()).isEqualTo("REVIEWER");
        assertThat(preflight.consent().required()).isFalse();
        assertThat(preflight.earliestFireTime()).isEqualTo(OffsetDateTime.parse("2026-09-04T12:10:00Z"));
    }

    @Test
    void fromDraftTheNextMoveIsSubmittingForReview() {
        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);
        assertThat(preflight.nextTransition().to()).isEqualTo("IN_REVIEW");
        assertThat(preflight.nextTransition().label()).isEqualTo("Submit for review");
        assertThat(preflight.nextTransition().requiresReview()).isFalse();
        assertThat(preflight.ready()).isTrue();
    }

    @Test
    void aScheduledPostHasNoNextMove() {
        post.setCurrentStatus("SCHEDULED");
        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);
        assertThat(preflight.nextTransition()).isNull();
    }

    @Test
    void anApprovedPostsNextGateMoveIsScheduling() {
        post.setCurrentStatus("APPROVED");
        when(workflowService.isReviewSatisfied(eq(PROJECT), eq(post), any())).thenReturn(true);
        when(consentService.verdict(post)).thenReturn(PublishConsentService.Verdict.SUPERSEDED);
        PostPublishTarget target = new PostPublishTarget();
        target.setId("t-1");
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(target));

        PublishPreflightService.Preflight preflight = service.preflight(PROJECT, "post-1", caller);

        assertThat(preflight.nextTransition().to()).isEqualTo("SCHEDULED");
        assertThat(preflight.nextTransition().requiresReview()).isFalse();
        assertThat(preflight.review().satisfied()).isTrue();
        assertThat(preflight.consent().required()).isTrue();
        assertThat(preflight.consent().verdict()).isEqualTo(PublishConsentService.Verdict.SUPERSEDED);
    }

    private static User user(String id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
