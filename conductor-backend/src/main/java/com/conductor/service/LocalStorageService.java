package com.conductor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Profile("local")
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path storagePath;
    private final String serverBaseUrl;

    public LocalStorageService(
            @Value("${local.storage.path:./local-uploads}") String storagePath,
            @Value("${server.base-url:http://localhost:8080}") String serverBaseUrl) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath();
        this.serverBaseUrl = serverBaseUrl;
    }

    @Override
    public void upload(String gcsPath, byte[] content, String contentType) {
        try {
            Path target = storagePath.resolve(gcsPath);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new RuntimeException("Local storage upload failed: " + gcsPath, e);
        }
    }

    @Override
    public String generateSignedUrl(String gcsPath, int expiryMinutes) {
        String encoded = UriUtils.encodePath(gcsPath, StandardCharsets.UTF_8);
        return serverBaseUrl + "/api/v1/local-files/" + encoded;
    }

    @Override
    public void delete(String gcsPath) {
        try {
            Path target = storagePath.resolve(gcsPath);
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to delete local file '{}': {}", gcsPath, e.getMessage());
        }
    }

    @Override
    public boolean isHealthy() {
        return Files.isWritable(storagePath) || tryCreateStorageDir();
    }

    private boolean tryCreateStorageDir() {
        try {
            Files.createDirectories(storagePath);
            return Files.isWritable(storagePath);
        } catch (IOException e) {
            return false;
        }
    }
}
