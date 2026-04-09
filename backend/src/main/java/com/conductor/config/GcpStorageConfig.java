package com.conductor.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.api.services.storage.StorageScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

@Configuration
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
}
