package com.conductor.service.view;

import java.time.OffsetDateTime;

/**
 * A document with its content and (optionally) a freshly-minted signed storage URL. Assembled inside the service
 * transaction — the signed URL is generated there — so controllers can map it to their API DTO directly.
 */
public record DocumentView(
        String id,
        String workItemId,
        String filename,
        String contentType,
        OffsetDateTime createdAt,
        String content,
        String storagePath,
        String storageUrl,
        OffsetDateTime storageUrlExpiresAt,
        OffsetDateTime updatedAt) {
}
