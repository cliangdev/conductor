package com.conductor.service;

public interface StorageService {
    void upload(String gcsPath, byte[] content, String contentType);
    byte[] download(String gcsPath);
    String generateSignedUrl(String gcsPath, int expiryMinutes);
    void delete(String gcsPath);
    boolean isHealthy();
}
