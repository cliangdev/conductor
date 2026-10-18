package com.conductor.config;

import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.api.services.storage.StorageScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

@Configuration
@Profile("!local")
public class GcpStorageConfig {

    @Value("${gcp.storage.bucket-name}")
    private String bucketName;

    @Value("${gcp.service-account-key:}")
    private String serviceAccountKeyBase64;

    @Value("${gcp.signed-url.expiry-minutes:15}")
    private int signedUrlExpiryMinutes;

    public String getBucketName() {
        return bucketName;
    }

    public int getSignedUrlExpiryMinutes() {
        return signedUrlExpiryMinutes;
    }

    @Bean
    public Storage storage() throws IOException {
        if (serviceAccountKeyBase64 != null && !serviceAccountKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(serviceAccountKeyBase64);
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(keyBytes))
                    .createScoped(StorageScopes.CLOUD_PLATFORM);
            return StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        }
        return StorageOptions.getDefaultInstance().getService();
    }

    // HTTP/JSON transport avoids spinning up a gRPC channel for KMS, reducing memory pressure.
    // Application default credentials (GCP_SERVICE_ACCOUNT_KEY env var) are picked up automatically.
    @Bean(destroyMethod = "close")
    public KeyManagementServiceClient kmsClient() throws Exception {
        return KeyManagementServiceClient.create(kmsSettings());
    }

    // The defaults give one attempt the whole 60s budget and don't retry UNKNOWN, which is how a
    // dropped pooled connection surfaces ("Error writing request body to server"). Scheduled feed
    // pulls hit both on a CPU-throttled instance, so: short per-attempt timeouts, and retry UNKNOWN.
    // Retrying encrypt is safe: a repeat only yields a different ciphertext for the same DEK.
    static KeyManagementServiceSettings kmsSettings() throws IOException {
        KeyManagementServiceSettings.Builder builder = KeyManagementServiceSettings.newHttpJsonBuilder();
        Set<StatusCode.Code> retryable = Set.of(
                StatusCode.Code.UNAVAILABLE, StatusCode.Code.DEADLINE_EXCEEDED, StatusCode.Code.UNKNOWN);
        RetrySettings retry = builder.decryptSettings().getRetrySettings().toBuilder()
                .setInitialRpcTimeoutDuration(Duration.ofSeconds(10))
                .setMaxRpcTimeoutDuration(Duration.ofSeconds(20))
                .setRpcTimeoutMultiplier(1.5)
                .setTotalTimeoutDuration(Duration.ofSeconds(60))
                .build();
        builder.decryptSettings().setRetryableCodes(retryable).setRetrySettings(retry);
        builder.encryptSettings().setRetryableCodes(retryable).setRetrySettings(retry);
        return builder.build();
    }
}
