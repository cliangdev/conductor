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
        String avatarUrl) {
}
