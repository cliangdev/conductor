package com.conductor.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;
import com.google.api.gax.core.FixedCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Credentials for the Cloud Tasks client, used by {@link com.conductor.workflow.CloudTasksJobDispatcher}
 * to push workflow job dispatch as HTTP requests. Mirrors {@link CloudRunJobsConfig}'s credential
 * resolution exactly: explicit base64 service-account key (`gcp.service-account-key`) when set,
 * otherwise application default credentials. Reuses {@link CloudRunJobsConfig}'s executor sizing —
 * same rationale (a shared client's default gax executor is undersized for concurrent fan-out).
 */
@Configuration
@Profile("!local")
public class CloudTasksConfig {

    @Value("${gcp.service-account-key:}")
    private String serviceAccountKeyBase64;

    private GoogleCredentials credentials() throws IOException {
        if (serviceAccountKeyBase64 != null && !serviceAccountKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(serviceAccountKeyBase64);
            return GoogleCredentials.fromStream(new ByteArrayInputStream(keyBytes));
        }
        return null;
    }

    @Bean(destroyMethod = "close")
    public CloudTasksClient cloudTasksClient() throws IOException {
        CloudTasksSettings.Builder settings =
                CloudTasksSettings.newBuilder().setExecutorProvider(CloudRunJobsConfig.cloudRunExecutorProvider());
        GoogleCredentials credentials = credentials();
        if (credentials != null) {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return CloudTasksClient.create(settings.build());
    }
}
