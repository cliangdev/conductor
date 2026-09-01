package com.conductor.service.view;

import java.time.OffsetDateTime;

/**
 * A submitted review enriched with the reviewer's display fields (joined from {@code users}). Assembled inside
 * the service transaction so controllers can map it to their API DTO without touching lazy associations.
 */
public record ReviewWithUser(
        String reviewerId,
        String verdict,
        OffsetDateTime submittedAt,
        String body,
        String name,
        String avatarUrl,
        /**
         * Whether this review still describes the Work Item as it stands, and so still counts toward the
         * review gate. False for one cast in an earlier review round, or against a different publish
         * bundle. Computed the same way {@code WorkItemWorkflowService.hasCurrentApprovedReview} computes
         * it, so a client cannot render "approved" for a review the gate is ignoring.
         */
        boolean current) {
}
