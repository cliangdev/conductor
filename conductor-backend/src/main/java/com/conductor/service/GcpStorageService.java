package com.conductor.service;

import com.conductor.exception.StorageUploadException;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.concurrent.TimeUnit;

@Service
public class GcpStorageService {

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

    public boolean isHealthy() {
        try {
            storage.get(bucketName, Storage.BucketGetOption.fields(Storage.BucketField.NAME));
            return true;
        } catch (Exception e) {
            log.warn("GCS health check failed: {}", e.getMessage());
            return false;
        }
    }

    public void delete(String gcsPath) {
        try {
            storage.delete(BlobId.of(bucketName, gcsPath));
        } catch (Exception e) {
            log.warn("Failed to delete GCS object '{}': {}", gcsPath, e.getMessage());
        }
    }

    public String generateSignedUrl(String gcsPath, int expiryMinutes) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath)).build();
        URL url = storage.signUrl(blobInfo, expiryMinutes, TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature());
        return url.toString();
    }
}
