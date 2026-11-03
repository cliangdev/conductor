package com.conductor.creative;

import com.conductor.service.AssetUploadPolicy;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;

/**
 * What a slide-text edit does to a Post, decided from the Post's status on its own Workflow's statechart (never a
 * hardcoded status list): the one rule {@link CreativeEditPropagator} applies and the {@code affected-posts} read
 * announces before anyone saves.
 */
enum CreativeEditImpact {
    /** Past the point of change (published, or scheduled on a Workflow with no review gate): left alone. */
    UNCHANGED("unchanged"),
    /** Approved or Scheduled: unscheduled if need be, back to the review status, a new review round. */
    BACK_TO_REVIEW("back-to-review"),
    /** In review: stays there, a new review round (approvals so far no longer count). */
    NEW_ROUND("new-round"),
    /** Before review (draft, changes requested): re-pointed at the new slides, nothing else moves. */
    UPDATED("updated");

    /** The value the API reports. {@code reviewer-fix} is {@link #NEW_ROUND} without the round bump. */
    static final String REVIEWER_FIX = "reviewer-fix";

    private final String wire;

    CreativeEditImpact(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static CreativeEditImpact of(Statechart chart, String status) {
        if (chart == null || status == null) {
            return UPDATED;
        }
        if (chart.isTerminal(status)) {
            return UNCHANGED;
        }
        if (AssetUploadPolicy.isApprovedOrLater(chart, status)) {
            return BACK_TO_REVIEW;
        }
        if (AssetUploadPolicy.reviewGate(chart).map(StatechartTransition::from).filter(status::equals).isPresent()) {
            return NEW_ROUND;
        }
        // A Workflow with no review gate freezes at scheduling, and there is nothing to send a Post back to.
        if (PublishingWorkflow.isScheduledOrLater(chart, status)) {
            return UNCHANGED;
        }
        return UPDATED;
    }
}
