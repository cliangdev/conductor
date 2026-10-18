package com.conductor.service;

import com.conductor.exception.StorageUploadException;
import com.conductor.observability.RequestStats;
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
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Profile("!local")
public class GcpStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(GcpStorageService.class);

    private final Storage storage;
    private final String bucketName;

    /** Objects are private to the signed-in viewer's browser; a day is long enough to stop re-downloads. */
    static final String CACHE_CONTROL = "private, max-age=86400";

    /** A cached signed URL is reused until this fraction of its lifetime has passed. */
    private static final double URL_REUSE_FRACTION = 0.75;
    private static final int URL_CACHE_MAX_ENTRIES = 5000;

    private record UrlKey(String gcsPath, int expiryMinutes) {}

    private record CachedUrl(String url, long reuseUntilMillis) {}

    private final ConcurrentHashMap<UrlKey, CachedUrl> urlCache = new ConcurrentHashMap<>();
    private final Clock clock;

    int[] retryDelays = {1000, 2000, 4000};

    @org.springframework.beans.factory.annotation.Autowired
    public GcpStorageService(Storage storage,
                             @Value("${gcp.storage.bucket-name}") String bucketName) {
        this(storage, bucketName, Clock.systemUTC());
    }

    GcpStorageService(Storage storage, String bucketName, Clock clock) {
        this.storage = storage;
        this.bucketName = bucketName;
        this.clock = clock;
    }

    public void upload(String gcsPath, byte[] content, String contentType) {
        long startNanos = System.nanoTime();
        try {
            doUpload(gcsPath, content, contentType);
        } finally {
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
    }

    private void doUpload(String gcsPath, byte[] content, String contentType) {
        int maxAttempts = 3;
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath))
                        .setContentType(contentType)
                        .setCacheControl(CACHE_CONTROL)
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
        long startNanos = System.nanoTime();
        byte[] content;
        try {
            content = storage.readAllBytes(BlobId.of(bucketName, gcsPath));
        } finally {
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
        if (content == null) {
            throw new jakarta.persistence.EntityNotFoundException("Storage object not found: " + gcsPath);
        }
        return content;
    }

    @Override
    public void copy(String sourceGcsPath, String destinationGcsPath) {
        long startNanos = System.nanoTime();
        try {
            storage.copy(com.google.cloud.storage.Storage.CopyRequest.newBuilder()
                    .setSource(BlobId.of(bucketName, sourceGcsPath))
                    .setTarget(BlobId.of(bucketName, destinationGcsPath))
                    .build()).getResult();
            markCacheable(destinationGcsPath);
        } finally {
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
    }

    public boolean isHealthy() {
        long startNanos = System.nanoTime();
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
        } finally {
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
    }

    public void delete(String gcsPath) {
        // Returns false when the object doesn't exist — that's success for an idempotent delete.
        // Real failures (auth, network) throw StorageException and propagate per the interface
        // contract, so callers keep their reference and retry instead of orphaning the object.
        long startNanos = System.nanoTime();
        try {
            storage.delete(BlobId.of(bucketName, gcsPath));
        } finally {
            evictSignedUrls(gcsPath);
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
    }

    /**
     * Returns the same signed URL for repeated calls until 75% of its lifetime has passed, so the browser
     * sees a stable URL per object and can serve it from cache. Upload URLs are never cached.
     */
    public String generateSignedUrl(String gcsPath, int expiryMinutes) {
        UrlKey key = new UrlKey(gcsPath, expiryMinutes);
        long now = clock.millis();
        CachedUrl cached = urlCache.get(key);
        if (cached != null && now < cached.reuseUntilMillis()) {
            return cached.url();
        }
        long startNanos = System.nanoTime();
        String signed;
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath)).build();
            URL url = storage.signUrl(blobInfo, expiryMinutes, TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature());
            signed = url.toString();
        } finally {
            RequestStats.recordSignedUrl(System.nanoTime() - startNanos);
        }
        if (urlCache.size() >= URL_CACHE_MAX_ENTRIES) {
            urlCache.values().removeIf(c -> now >= c.reuseUntilMillis());
            if (urlCache.size() >= URL_CACHE_MAX_ENTRIES) {
                urlCache.clear();
            }
        }
        long reuseMillis = (long) (expiryMinutes * 60_000L * URL_REUSE_FRACTION);
        urlCache.put(key, new CachedUrl(signed, now + reuseMillis));
        return signed;
    }

    private void evictSignedUrls(String gcsPath) {
        urlCache.keySet().removeIf(k -> k.gcsPath().equals(gcsPath));
    }

    @Override
    public void markCacheable(String gcsPath) {
        long startNanos = System.nanoTime();
        try {
            storage.update(BlobInfo.newBuilder(BlobId.of(bucketName, gcsPath))
                    .setCacheControl(CACHE_CONTROL)
                    .build());
        } catch (Exception e) {
            log.warn("Could not set Cache-Control on {}: {}", gcsPath, e.getMessage());
        } finally {
            RequestStats.recordGcsCall(System.nanoTime() - startNanos);
        }
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
        long startNanos = System.nanoTime();
        try {
            URL url = storage.signUrl(blobInfoBuilder.build(), expiryMinutes, TimeUnit.MINUTES,
                    options.toArray(new Storage.SignUrlOption[0]));
            return url.toString();
        } finally {
            RequestStats.recordSignedUrl(System.nanoTime() - startNanos);
        }
    }
}
