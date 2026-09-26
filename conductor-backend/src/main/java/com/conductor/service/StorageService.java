package com.conductor.service;

public interface StorageService {
    void upload(String gcsPath, byte[] content, String contentType);
    byte[] download(String gcsPath);

    /**
     * Server-side copy of one object to another path, without the bytes passing through this process —
     * used to move a Creative render frame into a Post's own asset prefix on attach (COND-24 T3), so the
     * two can be deleted, retained and billed independently.
     */
    void copy(String sourceGcsPath, String destinationGcsPath);
    String generateSignedUrl(String gcsPath, int expiryMinutes);
    /**
     * Deletes an object. Idempotent: a missing object is success. A real backend failure (auth,
     * network, quota) propagates as a runtime exception so callers cleaning up a stored reference can
     * keep the reference and retry later instead of orphaning the object.
     */
    void delete(String gcsPath);
    boolean isHealthy();

    /**
     * Generates a time-limited signed URL a caller can {@code PUT} raw bytes to directly, without the
     * backend ever handling the payload — used for workflow-artifact uploads.
     *
     * @param contentType the {@code Content-Type} the caller must send with its PUT; may be null
     * @return the signed upload URL, or {@code null} if this storage backend doesn't support signed
     *         uploads (e.g. {@link LocalStorageService}) — callers must fall back to a passthrough
     *         upload path in that case.
     */
    String generateSignedUploadUrl(String gcsPath, String contentType, int expiryMinutes);
}
