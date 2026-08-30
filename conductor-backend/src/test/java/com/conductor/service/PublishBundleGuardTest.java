package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * COND-23 T4.2 — the approval invariant: editing the publish bundle of an Approved-or-later Post reverts it
 * to the review status, voids the approval that no longer describes it, and gives back any native-lane
 * hand-off first.
 *
 * <p>Pure unit tests against the REAL seeded statecharts (MARKETING and ENGINEERING) plus two hand-written
 * ones, because the load-bearing claim is that no status name is hardcoded: the review status a revert lands
 * on is read off the workflow's own {@code requiresReview} edge, so a workflow that names its statuses
 * something else entirely gets the same behavior.
 */
class PublishBundleGuardTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String POST_ID = "post-1";
    private static final OffsetDateTime FIRE_TIME = OffsetDateTime.parse("2026-09-01T14:00:00Z");

    private WorkflowDefinitionResolver resolver;
    private NativeHandoffService nativeHandoffService;
    private PublishBundleHasher publishBundleHasher;
    private WorkItemRepository workItemRepository;
    private PublishBundleGuard guard;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        resolver = Mockito.mock(WorkflowDefinitionResolver.class);
        nativeHandoffService = Mockito.mock(NativeHandoffService.class);
        publishBundleHasher = Mockito.mock(PublishBundleHasher.class);
        workItemRepository = Mockito.mock(WorkItemRepository.class);
        guard = new PublishBundleGuard(resolver, nativeHandoffService, publishBundleHasher, workItemRepository);

        marketing = statechart("/schema/examples/marketing.workflow.json");
        engineering = statechart("/schema/examples/engineering.workflow.json");

        when(publishBundleHasher.appliesTo(any())).thenReturn(true);
        when(workItemRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    // --- [auto] Any bundle edit on an Approved or Scheduled Post reverts it to In Review ---------

    @Test
    void revertsAnApprovedPostToTheReviewStatus() {
        WorkItem post = marketingPost("APPROVED");

        Optional<PublishBundleGuard.Revert> revert = guard.revertForBundleEdit(PROJECT_ID, post);

        assertThat(revert).contains(new PublishBundleGuard.Revert("APPROVED", "IN_REVIEW"));
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
        verify(workItemRepository).save(post);
    }

    @Test
    void revertsAScheduledPostToTheReviewStatus() {
        WorkItem post = marketingPost("SCHEDULED");

        Optional<PublishBundleGuard.Revert> revert = guard.revertForBundleEdit(PROJECT_ID, post);

        assertThat(revert).contains(new PublishBundleGuard.Revert("SCHEDULED", "IN_REVIEW"));
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void revertsEveryNonTerminalStatusTheWorkflowPlacesAtOrBeyondItsReviewGate() {
        // PUBLISHED is deliberately absent: it is terminal, and a terminal Post is refused, not reverted.
        for (String status : List.of("APPROVED", "SCHEDULED", "FAILED")) {
            WorkItem post = marketingPost(status);

            assertThat(guard.revertForBundleEdit(PROJECT_ID, post)).isPresent();
            assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
        }
    }

    // --- [auto] A Published Post's audit trail is immutable: edits are refused, not reverted -----

    @Test
    void refusesABundleEditOnAPublishedPostInsteadOfRevertingIt() {
        WorkItem post = marketingPost("PUBLISHED");

        assertThatThrownBy(() -> guard.revertForBundleEdit(PROJECT_ID, post))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Published")
                .hasMessageContaining("immutable");

        assertThat(post.getCurrentStatus()).isEqualTo("PUBLISHED");
        assertThat(post.getCurrentReviewRound()).isZero();
        verify(workItemRepository, never()).save(any());
        verifyNoInteractions(nativeHandoffService);
    }

    @Test
    void refusesACaptionEditOnAPublishedPostThroughThePatchEntryPointToo() {
        WorkItem post = marketingPost("PUBLISHED");

        assertThatThrownBy(() -> guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post,
                "Rewritten after the fact", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("immutable");

        assertThat(post.getCurrentStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void aPatchThatChangesNoBundleFieldOnAPublishedPostIsStillANoOpRatherThanARefusal() {
        WorkItem post = marketingPost("PUBLISHED");

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, post.getDescription(), FIRE_TIME,
                "America/New_York")).isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void theImmutableStatusIsTheOneTheWorkflowMarksTerminalWhateverItIsCalled() {
        Statechart custom = parse("""
                {
                  "slug": "SIGNOFF", "area": "ops", "version": 1, "noun": "Notice",
                  "statuses": [
                    {"id": "WRITING", "label": "Writing", "initial": true},
                    {"id": "AWAITING_SIGNOFF", "label": "Awaiting Signoff"},
                    {"id": "CLEARED", "label": "Cleared"},
                    {"id": "SENT", "label": "Sent", "terminal": true}
                  ],
                  "transitions": [
                    {"from": "WRITING", "to": "AWAITING_SIGNOFF"},
                    {"from": "AWAITING_SIGNOFF", "to": "CLEARED", "requiresReview": true},
                    {"from": "CLEARED", "to": "SENT"}
                  ]
                }
                """);
        WorkItem sent = workItem("SIGNOFF", "SENT");
        givenStatechart(sent, custom);

        assertThatThrownBy(() -> guard.revertForBundleEdit(PROJECT_ID, sent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Notice")
                .hasMessageContaining("Sent");

        WorkItem cleared = workItem("SIGNOFF", "CLEARED");
        givenStatechart(cleared, custom);
        assertThat(guard.revertForBundleEdit(PROJECT_ID, cleared)).isPresent();
    }

    // --- [auto] A Failed Post is still editable so it can be fixed and retried -------------------

    @Test
    void stillRevertsAFailedPostSoItCanBeFixedBeforeARetry() {
        WorkItem post = marketingPost("FAILED");

        Optional<PublishBundleGuard.Revert> revert =
                guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, "Fixed caption", null, null);

        assertThat(revert).contains(new PublishBundleGuard.Revert("FAILED", "IN_REVIEW"));
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
        verify(workItemRepository).save(post);
    }

    // --- [auto] The review status is resolved from the statechart, not hardcoded -----------------

    @Test
    void landsOnTheReviewStatusTheWorkflowDeclaresWhateverItIsNamed() {
        Statechart custom = parse("""
                {
                  "slug": "SIGNOFF", "area": "ops", "version": 1, "noun": "Notice",
                  "statuses": [
                    {"id": "WRITING", "label": "Writing", "initial": true},
                    {"id": "AWAITING_SIGNOFF", "label": "Awaiting Signoff"},
                    {"id": "CLEARED", "label": "Cleared"},
                    {"id": "SENT", "label": "Sent", "terminal": true}
                  ],
                  "transitions": [
                    {"from": "WRITING", "to": "AWAITING_SIGNOFF"},
                    {"from": "AWAITING_SIGNOFF", "to": "CLEARED", "requiresReview": true},
                    {"from": "CLEARED", "to": "SENT"}
                  ]
                }
                """);
        WorkItem post = workItem("SIGNOFF", "CLEARED");
        givenStatechart(post, custom);

        Optional<PublishBundleGuard.Revert> revert = guard.revertForBundleEdit(PROJECT_ID, post);

        assertThat(revert).contains(new PublishBundleGuard.Revert("CLEARED", "AWAITING_SIGNOFF"));
        assertThat(post.getCurrentStatus()).isEqualTo("AWAITING_SIGNOFF");
    }

    @Test
    void leavesAWorkflowWithNoReviewGateAlone() {
        Statechart ungated = parse("""
                {
                  "slug": "FLAT", "area": "ops", "version": 1, "noun": "Note",
                  "statuses": [
                    {"id": "OPEN", "label": "Open", "initial": true},
                    {"id": "CLOSED", "label": "Closed", "terminal": true}
                  ],
                  "transitions": [{"from": "OPEN", "to": "CLOSED"}]
                }
                """);
        WorkItem post = workItem("FLAT", "CLOSED");
        givenStatechart(post, ungated);

        assertThat(guard.revertForBundleEdit(PROJECT_ID, post)).isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("CLOSED");
        verifyNoInteractions(nativeHandoffService);
    }

    // --- [auto] Editing a Draft / In Review / Changes Requested Post changes nothing -------------

    @Test
    void leavesAPreApprovalPostAtItsCurrentStatus() {
        for (String status : List.of("DRAFT", "IN_REVIEW", "CHANGES_REQUESTED")) {
            WorkItem post = marketingPost(status);

            assertThat(guard.revertForBundleEdit(PROJECT_ID, post)).isEmpty();
            assertThat(post.getCurrentStatus()).isEqualTo(status);
        }
        verifyNoInteractions(nativeHandoffService);
        verify(workItemRepository, never()).save(any());
    }

    // --- [auto] An ENGINEERING work item is completely unaffected --------------------------------

    @Test
    void leavesAnEngineeringItemAloneBecauseItCarriesNoPublishBundle() {
        WorkItem issue = workItem("ENGINEERING", "DONE");
        givenStatechart(issue, engineering);
        when(publishBundleHasher.appliesTo(issue)).thenReturn(false);

        assertThat(guard.revertForBundleEdit(PROJECT_ID, issue)).isEmpty();
        assertThat(issue.getCurrentStatus()).isEqualTo("DONE");
        verifyNoInteractions(nativeHandoffService, resolver);
        verify(workItemRepository, never()).save(any());
    }

    @Test
    void leavesAPostWithNoPublishTargetsAloneEvenWhenItIsApproved() {
        WorkItem post = marketingPost("APPROVED");
        when(publishBundleHasher.appliesTo(post)).thenReturn(false);

        assertThat(guard.revertForBundleEdit(PROJECT_ID, post)).isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("APPROVED");
    }

    // --- [auto] Native handoffs are revoked before the status change -----------------------------

    @Test
    void revokesNativeHandoffsBeforeItWritesTheRevertedStatus() {
        WorkItem post = marketingPost("SCHEDULED");
        List<String> statusSeenByUnschedule = new ArrayList<>();
        doAnswer(call -> statusSeenByUnschedule.add(post.getCurrentStatus()))
                .when(nativeHandoffService).unschedule(post);

        guard.revertForBundleEdit(PROJECT_ID, post);

        assertThat(statusSeenByUnschedule).containsExactly("SCHEDULED");
        InOrder order = inOrder(nativeHandoffService, workItemRepository);
        order.verify(nativeHandoffService).unschedule(post);
        order.verify(workItemRepository).save(post);
    }

    @Test
    void doesNotRevokeWhenThePostIsApprovedButNotScheduled() {
        WorkItem post = marketingPost("APPROVED");

        guard.revertForBundleEdit(PROJECT_ID, post);

        verify(nativeHandoffService, never()).unschedule(any());
    }

    // --- [auto] A revocation failure refuses the edit and leaves the Post scheduled --------------

    @Test
    void propagatesARevocationFailureAndNeverWritesTheRevert() {
        WorkItem post = marketingPost("SCHEDULED");
        doThrow(new BusinessException("Could not revoke scheduled facebook post page_1_post_99"))
                .when(nativeHandoffService).unschedule(post);

        assertThatThrownBy(() -> guard.revertForBundleEdit(PROJECT_ID, post))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Could not revoke");

        assertThat(post.getCurrentStatus()).isEqualTo("SCHEDULED");
        verify(workItemRepository, never()).save(any());
    }

    // --- [auto] The prior approval is voided, not just out-hashed -------------------------------

    @Test
    void opensANewReviewRoundSoTheStandingApprovalCanNoLongerSatisfyTheGate() {
        WorkItem post = marketingPost("APPROVED");
        post.setCurrentReviewRound(2);

        guard.revertForBundleEdit(PROJECT_ID, post);

        assertThat(post.getCurrentReviewRound()).isEqualTo(3);
    }

    // --- [auto] Only a real change to a bundle field triggers the revert -------------------------

    @Test
    void revertsWhenTheCaptionActuallyChanges() {
        WorkItem post = marketingPost("APPROVED");

        Optional<PublishBundleGuard.Revert> revert =
                guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, "Rewritten caption", null, null);

        assertThat(revert).isPresent();
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void revertsWhenTheFireTimeMoves() {
        WorkItem post = marketingPost("APPROVED");

        Optional<PublishBundleGuard.Revert> revert = guard.revertForCaptionOrScheduleEdit(
                PROJECT_ID, post, null, FIRE_TIME.plusDays(1), null);

        assertThat(revert).isPresent();
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void revertsWhenTheScheduleTimezoneChanges() {
        WorkItem post = marketingPost("APPROVED");

        Optional<PublishBundleGuard.Revert> revert =
                guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, null, null, "Europe/Berlin");

        assertThat(revert).isPresent();
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void revertsWhenABlankTimezoneClearsAZoneThatWasSet() {
        WorkItem post = marketingPost("APPROVED");

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, null, null, "")).isPresent();
    }

    @Test
    void doesNotRevertWhenThePatchTouchesNoBundleField() {
        WorkItem post = marketingPost("APPROVED");

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, null, null, null)).isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("APPROVED");
        verifyNoInteractions(nativeHandoffService, resolver, publishBundleHasher);
    }

    @Test
    void doesNotRevertWhenThePatchResendsTheSameValues() {
        WorkItem post = marketingPost("APPROVED");

        Optional<PublishBundleGuard.Revert> revert = guard.revertForCaptionOrScheduleEdit(
                PROJECT_ID, post, post.getDescription(), FIRE_TIME, "America/New_York");

        assertThat(revert).isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("APPROVED");
    }

    @Test
    void treatsAnEquivalentOffsetAsTheSameFireTime() {
        WorkItem post = marketingPost("APPROVED");
        OffsetDateTime sameInstantOtherOffset =
                FIRE_TIME.withOffsetSameInstant(ZoneOffset.ofHours(-4));

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, null, sameInstantOtherOffset, null))
                .isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("APPROVED");
    }

    @Test
    void doesNotRevertAPreApprovalPostEvenWhenTheCaptionChanges() {
        WorkItem post = marketingPost("DRAFT");

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, "Rewritten caption", null, null))
                .isEmpty();
        assertThat(post.getCurrentStatus()).isEqualTo("DRAFT");
    }

    @Test
    void treatsAFirstFireTimeOnAPostThatHadNoneAsAChange() {
        WorkItem post = marketingPost("APPROVED");
        post.setScheduledFor(null);

        assertThat(guard.revertForCaptionOrScheduleEdit(PROJECT_ID, post, null, FIRE_TIME, null)).isPresent();
    }

    // --- null tolerance -------------------------------------------------------------------------

    @Test
    void toleratesANullOrUnsavedWorkItem() {
        assertThat(guard.revertForBundleEdit(PROJECT_ID, null)).isEmpty();
        assertThat(guard.revertForBundleEdit(PROJECT_ID, new WorkItem())).isEmpty();
        verifyNoInteractions(nativeHandoffService, resolver);
    }

    // --- helpers --------------------------------------------------------------------------------

    private WorkItem marketingPost(String status) {
        WorkItem post = workItem("MARKETING", status);
        post.setDescription("Doors open at nine.");
        post.setScheduledFor(FIRE_TIME);
        post.setScheduleTimezone("America/New_York");
        givenStatechart(post, marketing);
        return post;
    }

    private WorkItem workItem(String workflow, String status) {
        WorkItem item = new WorkItem();
        item.setId(POST_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        item.setProject(project);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        return item;
    }

    private void givenStatechart(WorkItem item, Statechart chart) {
        when(resolver.resolveRequired(eq(PROJECT_ID), eq(item.getWorkflow()), any())).thenReturn(chart);
    }

    private static Statechart statechart(String resource) {
        try (InputStream in = PublishBundleGuardTest.class.getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Statechart parse(String json) {
        try {
            return Statechart.parse(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
