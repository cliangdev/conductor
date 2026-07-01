package com.conductor.service.view;

/**
 * A reviewer assignment enriched with the user's display fields (joined from {@code users}). {@code reviewVerdict}
 * mirrors the current v1/v2 behavior of being left null. Assembled inside the service transaction.
 */
public record ReviewerView(
        String userId,
        String name,
        String email,
        String avatarUrl,
        String reviewVerdict) {
}
