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
        // normalize(), not just toAbsolutePath(): the default value is the RELATIVE "./local-uploads",
        // whose absolute form keeps a literal "." segment. resolveWithinStorageRoot compares a normalized
        // target against this root, so without normalizing here every legitimate path "escapes" the root
        // and no file can ever be stored.
        this.storagePath = Paths.get(storagePath).toAbsolutePath().normalize();
        this.serverBaseUrl = serverBaseUrl;
    }

    @Override
    public void upload(String gcsPath, byte[] content, String contentType) {
        Path target = resolveWithinStorageRoot(gcsPath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new RuntimeException("Local storage upload failed: " + gcsPath, e);
        }
    }

    @Override
    public byte[] download(String gcsPath) {
        Path target = resolveWithinStorageRoot(gcsPath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new jakarta.persistence.EntityNotFoundException("Local storage object not found: " + gcsPath);
        }
    }

    /**
     * Defense in depth against a {@code gcsPath} containing {@code ../} segments: the caller-facing
     * validation ({@code ArtifactSpec.NAME_PATTERN} et al.) should already reject that, but this is the
     * last line of defense before touching the filesystem — mirrors {@code LocalFileController}'s guard
     * on the read side.
     */
    private Path resolveWithinStorageRoot(String gcsPath) {
        Path target = storagePath.resolve(gcsPath).normalize();
        if (!target.startsWith(storagePath)) {
            throw new SecurityException("Storage path escapes storage root: " + gcsPath);
        }
        return target;
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
            // Propagate per the interface contract — a missing file is success (deleteIfExists),
            // but a real IO failure must reach the caller so it can keep its reference and retry.
            throw new java.io.UncheckedIOException("Failed to delete local file '" + gcsPath + "'", e);
        }
    }

    @Override
    public boolean isHealthy() {
        return Files.isWritable(storagePath) || tryCreateStorageDir();
    }

    /**
     * Local storage has no signed-URL mechanism (there's no separate object-storage service to sign
     * against) — always returns null so callers fall back to a passthrough upload endpoint that reads
     * the body directly and calls {@link #upload}.
     */
    @Override
    public String generateSignedUploadUrl(String gcsPath, String contentType, int expiryMinutes) {
        return null;
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
