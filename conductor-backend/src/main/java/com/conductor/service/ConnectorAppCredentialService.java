package com.conductor.service;

import com.conductor.entity.ConnectorAppCredential;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectorAppCredentialRepository;
import com.conductor.workflow.WorkflowSecretsEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the OAuth <em>app</em> credentials a connector authenticates as, per project, falling
 * back to the deployment environment variables.
 *
 * <p>Resolution order is fixed and deliberately additive:
 * <ol>
 *   <li>the project's own {@link ConnectorAppCredential} row → {@link CredentialSource#PROJECT}</li>
 *   <li>the deployment env vars named by {@link OAuth2Connector#clientIdProperty()} /
 *       {@link OAuth2Connector#clientSecretProperty()} → {@link CredentialSource#DEPLOYMENT}</li>
 *   <li>neither → {@link CredentialSource#NONE}, carrying the property names that are missing</li>
 * </ol>
 * A project with no row resolves exactly what the deployment resolved before this class existed, so
 * every existing deployment keeps working untouched and one workspace's own app never leaks into
 * another's flows.
 *
 * <p><b>Crypto.</b> {@code CredentialService} (the connector envelope) is {@link
 * com.conductor.entity.Connection}-shaped — every method takes a {@code Connection} and stores the
 * wrapped DEK in its {@code kms_key_reference} column — so it cannot encrypt for an owner that is
 * not a connection. This class therefore reuses {@link WorkflowSecretsEncryptionService}, the
 * existing owner-agnostic AES-256-GCM seam already trusted with project-scoped secrets, rather than
 * hand-rolling crypto or widening the connection envelope.
 *
 * <p>{@link #resolve} is the only path that yields a plaintext secret, and exists for the OAuth flow
 * itself. Everything a human or an API response needs comes from {@link #status}, which masks the
 * secret to its last four characters.
 */
@Service
public class ConnectorAppCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAppCredentialService.class);

    /** Where the effective credentials came from. */
    public enum CredentialSource {
        /** The project brought its own platform app. */
        PROJECT,
        /** No project row; the deployment's environment variables supplied them. */
        DEPLOYMENT,
        /** Neither — the connector cannot start an OAuth flow at all. */
        NONE
    }

    /**
     * The effective credentials for one (project, connector), plus where they came from. The secret
     * is plaintext: hand this only to code that must talk to the provider.
     *
     * @param missingProperties env var names that would have to be set for a
     *        {@link CredentialSource#DEPLOYMENT} resolve to succeed; empty unless
     *        {@link CredentialSource#NONE}.
     */
    public record ResolvedAppCredentials(String connectorId, CredentialSource source, String clientId,
                                         String clientSecret, List<String> missingProperties) {
        public boolean configured() {
            return source != CredentialSource.NONE;
        }
    }

    /**
     * The display view of the same resolution: never carries the secret, only its last four
     * characters. {@code updatedBy}/{@code updatedAt} are null for a {@link CredentialSource#DEPLOYMENT}
     * or {@link CredentialSource#NONE} resolve — nobody set those through the product.
     */
    public record AppCredentialStatus(String connectorId, CredentialSource source, String clientId,
                                      String clientSecretLast4, List<String> missingProperties,
                                      String updatedBy, OffsetDateTime updatedAt) {
        public boolean configured() {
            return source != CredentialSource.NONE;
        }
    }

    private final ConnectorAppCredentialRepository repository;
    private final WorkflowSecretsEncryptionService encryptionService;
    private final Environment environment;
    private final ProjectSecurityService projectSecurityService;

    public ConnectorAppCredentialService(ConnectorAppCredentialRepository repository,
                                         WorkflowSecretsEncryptionService encryptionService,
                                         Environment environment,
                                         ProjectSecurityService projectSecurityService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.environment = environment;
        this.projectSecurityService = projectSecurityService;
    }

    /**
     * The credentials this project's OAuth flow for this connector must use, decrypted.
     *
     * <p>Not {@code @Transactional}: the row load takes its own short transaction from the
     * repository and the decrypt happens after it closes, mirroring
     * {@code ProviderCredentialService#resolveApiKey}.
     */
    public ResolvedAppCredentials resolve(String projectId, OAuth2Connector connector) {
        ConnectorAppCredential row = projectId == null ? null
                : repository.findByProjectIdAndConnectorId(projectId, connector.getId()).orElse(null);
        if (row != null) {
            return new ResolvedAppCredentials(connector.getId(), CredentialSource.PROJECT,
                    row.getClientId(), encryptionService.decrypt(row.getClientSecretEncrypted()), List.of());
        }
        return resolveFromDeployment(connector);
    }

    /** Masked counterpart to {@link #resolve}, for anything that only needs presence and provenance. */
    public AppCredentialStatus status(String projectId, OAuth2Connector connector) {
        ConnectorAppCredential row = projectId == null ? null
                : repository.findByProjectIdAndConnectorId(projectId, connector.getId()).orElse(null);
        return toStatus(connector, row);
    }

    /**
     * {@link #status} for a whole catalog in one query rather than one per connector — the shape the
     * connector list needs.
     */
    public List<AppCredentialStatus> statuses(String projectId, Collection<? extends OAuth2Connector> connectors) {
        Map<String, ConnectorAppCredential> byConnector = projectId == null ? Map.of()
                : repository.findByProjectId(projectId).stream()
                        .collect(Collectors.toMap(ConnectorAppCredential::getConnectorId, row -> row));
        return connectors.stream()
                .map(connector -> toStatus(connector, byConnector.get(connector.getId())))
                .toList();
    }

    /**
     * Stores (or replaces) this project's own app credentials for a connector. ADMIN only: these
     * credentials decide which platform application every member's consent flow runs as.
     */
    @Transactional
    public void put(String projectId, String connectorId, String clientId, String clientSecret, User caller) {
        requireProjectAdmin(projectId, caller);
        if (clientId == null || clientId.isBlank()) {
            throw new BusinessException("clientId is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new BusinessException("clientSecret is required");
        }
        ConnectorAppCredential row = repository.findByProjectIdAndConnectorId(projectId, connectorId)
                .orElseGet(() -> {
                    ConnectorAppCredential created = new ConnectorAppCredential();
                    created.setProjectId(projectId);
                    created.setConnectorId(connectorId);
                    return created;
                });
        row.setClientId(clientId.trim());
        row.setClientSecretEncrypted(encryptionService.encrypt(clientSecret));
        row.setUpdatedBy(caller.getId());
        repository.save(row);
        log.info("Connector app credential set for project={} connector={} by user={}",
                projectId, connectorId, caller.getId());
    }

    /**
     * Drops this project's own app credentials for a connector, returning it to the deployment env
     * vars. ADMIN only. A no-op when the project never had a row.
     */
    @Transactional
    public void clear(String projectId, String connectorId, User caller) {
        requireProjectAdmin(projectId, caller);
        repository.findByProjectIdAndConnectorId(projectId, connectorId).ifPresent(row -> {
            repository.delete(row);
            log.info("Connector app credential cleared for project={} connector={} by user={}",
                    projectId, connectorId, caller.getId());
        });
    }

    private ResolvedAppCredentials resolveFromDeployment(OAuth2Connector connector) {
        String clientId = property(connector.clientIdProperty());
        String clientSecret = property(connector.clientSecretProperty());
        List<String> missing = new ArrayList<>();
        if (isBlank(clientId)) {
            missing.add(connector.clientIdProperty());
        }
        if (isBlank(clientSecret)) {
            missing.add(connector.clientSecretProperty());
        }
        if (!missing.isEmpty()) {
            return new ResolvedAppCredentials(connector.getId(), CredentialSource.NONE, null, null,
                    List.copyOf(missing));
        }
        return new ResolvedAppCredentials(connector.getId(), CredentialSource.DEPLOYMENT, clientId,
                clientSecret, List.of());
    }

    private AppCredentialStatus toStatus(OAuth2Connector connector, ConnectorAppCredential row) {
        if (row != null) {
            return new AppCredentialStatus(connector.getId(), CredentialSource.PROJECT, row.getClientId(),
                    last4(encryptionService.decrypt(row.getClientSecretEncrypted())), List.of(),
                    row.getUpdatedBy(), row.getUpdatedAt());
        }
        ResolvedAppCredentials deployment = resolveFromDeployment(connector);
        return new AppCredentialStatus(connector.getId(), deployment.source(), deployment.clientId(),
                last4(deployment.clientSecret()), deployment.missingProperties(), null, null);
    }

    private void requireProjectAdmin(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new ForbiddenException("Caller is not a project admin");
        }
    }

    /** Reads the env var the same way {@code OAuthFlowService} did, so the fallback is unchanged. */
    private String property(String name) {
        return environment.getProperty(name, "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Enough of the secret to recognise which one is stored, never enough to use it. */
    private static String last4(String secret) {
        if (isBlank(secret)) {
            return null;
        }
        return secret.length() <= 4 ? "****" : secret.substring(secret.length() - 4);
    }
}
