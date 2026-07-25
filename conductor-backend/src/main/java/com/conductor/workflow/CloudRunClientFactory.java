package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ConnectionContext;
import com.conductor.service.ConnectionService;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.ExecutionsSettings;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.JobsSettings;
import com.google.cloud.run.v2.TasksClient;
import com.google.cloud.run.v2.TasksSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link JobsClient}/{@link ExecutionsClient}/{@link TasksClient} triple to use for a
 * given {@link CloudRunTarget}. The builtin target ({@code connectionId == null}) always uses the
 * injected, operator-configured default beans (see {@code CloudRunJobsConfig}); a customer target
 * gets its own per-connection clients, built from that connection's decrypted service-account key
 * and cached for reuse (constructing a gRPC client per execution would be wasteful).
 *
 * <p>There is no SPI hook for "connection changed/removed" — {@link com.conductor.service.RuntimeTargetService}
 * explicitly calls {@link #evict} from its delete/update paths whenever a target's connection is
 * removed or its config changes, so a stale cached client is never used past that point.
 */
@Component
@Profile("!local")
public class CloudRunClientFactory {

    private static final Logger log = LoggerFactory.getLogger(CloudRunClientFactory.class);

    private final JobsClient defaultJobsClient;
    private final ExecutionsClient defaultExecutionsClient;
    private final TasksClient defaultTasksClient;
    private final ConnectionService connectionService;
    private final ClientsBuilder clientsBuilder;
    private final ConcurrentHashMap<String, Clients> perConnectionClients = new ConcurrentHashMap<>();

    // @Autowired is load-bearing: with two constructors and no no-arg fallback, Spring cannot pick a
    // candidate on its own and the !local context fails at startup — which no test catches, because
    // the whole backend suite runs under the local profile where this bean is excluded.
    @Autowired
    public CloudRunClientFactory(JobsClient defaultJobsClient,
                                 ExecutionsClient defaultExecutionsClient,
                                 TasksClient defaultTasksClient,
                                 ConnectionService connectionService) {
        this(defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                CloudRunClientFactory::buildClients);
    }

    /**
     * Test seam: injects a stub {@link ClientsBuilder} keyed off the raw service-account key string
     * (not a parsed {@link GoogleCredentials}) so tests never need a real PEM-bearing key — they can
     * stub the builder with any fake string and skip credential parsing entirely.
     */
    CloudRunClientFactory(JobsClient defaultJobsClient,
                          ExecutionsClient defaultExecutionsClient,
                          TasksClient defaultTasksClient,
                          ConnectionService connectionService,
                          ClientsBuilder clientsBuilder) {
        this.defaultJobsClient = defaultJobsClient;
        this.defaultExecutionsClient = defaultExecutionsClient;
        this.defaultTasksClient = defaultTasksClient;
        this.connectionService = connectionService;
        this.clientsBuilder = clientsBuilder;
    }

    /** Grouped Cloud Run v2 clients for one credential source (builtin or a customer connection). */
    public record Clients(JobsClient jobs, ExecutionsClient executions, TasksClient tasks) {}

    public Clients forTarget(CloudRunTarget target) {
        if (target.connectionId() == null) {
            return new Clients(defaultJobsClient, defaultExecutionsClient, defaultTasksClient);
        }
        return perConnectionClients.computeIfAbsent(target.connectionId(), this::buildForConnection);
    }

    /** Closes and evicts the cached clients for a connection, if any (safe to call when absent). */
    public void evict(String connectionId) {
        if (connectionId == null) {
            return;
        }
        Clients clients = perConnectionClients.remove(connectionId);
        if (clients == null) {
            return;
        }
        closeQuietly(clients.jobs());
        closeQuietly(clients.executions());
        closeQuietly(clients.tasks());
    }

    private Clients buildForConnection(String connectionId) {
        Connection connection = connectionService.getById(connectionId)
                .orElseThrow(() -> new IllegalStateException("Connection not found: " + connectionId));
        ConnectionContext ctx;
        try {
            ctx = connectionService.toContext(connection);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve credentials for connection " + connectionId + ": " + e.getMessage(), e);
        }
        String key = ctx.accessToken();
        if (key == null || key.isBlank()) {
            throw new BusinessException("Connection has no service-account key configured");
        }
        try {
            return clientsBuilder.build(key);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to build Cloud Run clients for connection " + connectionId + ": " + e.getMessage(), e);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Failed to close a Cloud Run client during eviction: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    interface ClientsBuilder {
        Clients build(String serviceAccountKeyJson) throws IOException;
    }

    private static Clients buildClients(String serviceAccountKeyJson) throws IOException {
        GoogleCredentials credentials =
                GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountKeyJson.getBytes(StandardCharsets.UTF_8)))
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
        FixedCredentialsProvider provider = FixedCredentialsProvider.create(credentials);
        JobsClient jobs = JobsClient.create(JobsSettings.newBuilder().setCredentialsProvider(provider).build());
        ExecutionsClient executions = ExecutionsClient.create(
                ExecutionsSettings.newBuilder().setCredentialsProvider(provider).build());
        TasksClient tasks = TasksClient.create(TasksSettings.newBuilder().setCredentialsProvider(provider).build());
        return new Clients(jobs, executions, tasks);
    }
}
