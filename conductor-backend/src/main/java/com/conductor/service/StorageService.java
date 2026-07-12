package com.conductor.service;

public interface StorageService {
    void upload(String gcsPath, byte[] content, String contentType);
    byte[] download(String gcsPath);
    String generateSignedUrl(String gcsPath, int expiryMinutes);
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
