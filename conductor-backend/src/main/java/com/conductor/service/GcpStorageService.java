package com.conductor.service;

import com.conductor.exception.StorageUploadException;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!local")
public class GcpStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(GcpStorageService.class);

    private final Storage storage;
    private final String bucketName;

    int[] retryDelays = {1000, 2000, 4000};

    public GcpStorageService(Storage storage,
                             @Value("${gcp.storage.bucket-name}") String bucketName) {
        this.storage = storage;
        this.bucketName = bucketName;
    }

    public void upload(String gcsPath, byte[] content, String contentType) {
        int maxAttempts = 3;
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath))
                        .setContentType(contentType)
                        .build();
                storage.create(blobInfo, content);
                return;
            } catch (Exception e) {
                lastEx = e;
                log.warn("GCS upload attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryDelays[attempt - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new StorageUploadException("Upload failed after " + maxAttempts + " attempts", lastEx);
    }

    public byte[] download(String gcsPath) {
        byte[] content = storage.readAllBytes(BlobId.of(bucketName, gcsPath));
        if (content == null) {
            throw new jakarta.persistence.EntityNotFoundException("Storage object not found: " + gcsPath);
        }
        return content;
    }

    public boolean isHealthy() {
        try {
            // Probe an OBJECT-level operation the app actually relies on (storage.objects.list), not a
            // bucket-metadata read (storage.buckets.get). The runtime service account is granted
            // roles/storage.objectAdmin (object ops) but not bucket.get, so a bucket-get probe reports a
            // false "unhealthy" even though uploads/downloads/signed URLs work. pageSize(1) keeps it cheap.
            storage.list(bucketName, Storage.BlobListOption.pageSize(1)).getValues();
            return true;
        } catch (Exception e) {
            log.warn("GCS health check failed: {}", e.getMessage());
            return false;
        }
    }

    public void delete(String gcsPath) {
        // Returns false when the object doesn't exist — that's success for an idempotent delete.
        // Real failures (auth, network) throw StorageException and propagate per the interface
        // contract, so callers keep their reference and retry instead of orphaning the object.
        storage.delete(BlobId.of(bucketName, gcsPath));
    }

    public String generateSignedUrl(String gcsPath, int expiryMinutes) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath)).build();
        URL url = storage.signUrl(blobInfo, expiryMinutes, TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature());
        return url.toString();
    }

    @Override
    public String generateSignedUploadUrl(String gcsPath, String contentType, int expiryMinutes) {
        BlobInfo.Builder blobInfoBuilder = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath));
        boolean hasContentType = contentType != null && !contentType.isBlank();
        if (hasContentType) {
            blobInfoBuilder.setContentType(contentType);
        }
        List<Storage.SignUrlOption> options = new ArrayList<>();
        options.add(Storage.SignUrlOption.httpMethod(HttpMethod.PUT));
        options.add(Storage.SignUrlOption.withV4Signature());
        if (hasContentType) {
            // Bakes the Content-Type into the signature so the PUT must send the same header value.
            options.add(Storage.SignUrlOption.withContentType());
        }
        URL url = storage.signUrl(blobInfoBuilder.build(), expiryMinutes, TimeUnit.MINUTES,
                options.toArray(new Storage.SignUrlOption[0]));
        return url.toString();
    }
}
