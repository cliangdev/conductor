package com.conductor.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.ExecutionsSettings;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.JobsSettings;
import com.google.cloud.run.v2.TasksClient;
import com.google.cloud.run.v2.TasksSettings;
import com.google.api.gax.core.FixedCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Credentials for the Cloud Run Jobs v2 client, used by {@link com.conductor.workflow.GcpCloudRunJobLauncher}
 * to launch {@code claude-code} workflow steps as Cloud Run Job executions. Mirrors
 * {@link GcpStorageConfig}'s credential resolution exactly: explicit base64 service-account key
 * (`gcp.service-account-key`) when set, otherwise application default credentials.
 */
@Configuration
@Profile("!local")
public class CloudRunJobsConfig {

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
    public JobsClient jobsClient() throws IOException {
        JobsSettings.Builder settings = JobsSettings.newBuilder();
        GoogleCredentials credentials = credentials();
        if (credentials != null) {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return JobsClient.create(settings.build());
    }

    @Bean(destroyMethod = "close")
    public ExecutionsClient executionsClient() throws IOException {
        ExecutionsSettings.Builder settings = ExecutionsSettings.newBuilder();
        GoogleCredentials credentials = credentials();
        if (credentials != null) {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return ExecutionsClient.create(settings.build());
    }

    @Bean(destroyMethod = "close")
    public TasksClient tasksClient() throws IOException {
        TasksSettings.Builder settings = TasksSettings.newBuilder();
        GoogleCredentials credentials = credentials();
        if (credentials != null) {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(credentials));
        }
        return TasksClient.create(settings.build());
    }
}
