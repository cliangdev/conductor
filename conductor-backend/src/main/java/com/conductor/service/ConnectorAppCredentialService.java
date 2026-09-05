package com.conductor.service;

import com.conductor.entity.ConnectorAppCredential;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectorAppCredentialRepository;
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
 *       {@link OAuth2Connector#clientSecretProperty()} → {@link CredentialSource#DEPLOYMENT}, but only
 *       for a connector that {@link OAuth2Connector#allowsDeploymentCredentials() allows} them</li>
 *   <li>neither → {@link CredentialSource#NONE}, carrying the property names that are missing</li>
 * </ol>
 * A project with no row resolves exactly what the deployment resolved before this class existed, so
 * every existing deployment keeps working untouched and one workspace's own app never leaks into
 * another's flows.
 *
 * <p><b>The publishing platforms opt out of step 2.</b> A Meta, TikTok or YouTube app carries its own
 * App Review, its own rate limits and its own relationship with the creator whose account it posts to,
 * so it belongs to the workspace rather than to whoever runs the deployment. For those connectors this
 * class never reads the environment: no row means {@link CredentialSource#NONE} with <em>no</em>
 * missing-property names, which is how a caller tells "this workspace has entered nothing" apart from
 * "the deployment is missing an env var".
 *
 * <p><b>Crypto.</b> The client secret rides the same envelope as every other Integrations secret:
 * {@link CredentialService} generates a DEK for this row, wraps it with the KMS KEK into the row's
 * {@code kms_key_reference}, and encrypts the secret under it. That is one implementation shared with
 * connection tokens — reached through {@link com.conductor.entity.EnvelopeEncrypted} — not a second
 * copy of the crypto, and not the single deployment-wide key this table originally used.
 *
 * <p>{@link #resolve} is the only path that decrypts at all, and exists for the OAuth flow itself.
 * Everything a human or an API response needs comes from {@link #status}, which reads the stored
 * {@code client_secret_last4} and so never touches the ciphertext.
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
     *        {@link CredentialSource#NONE}, and empty even then for a connector that takes no
     *        deployment credentials at all — there is no env var that would fix it, only an admin
     *        entering the workspace's own app
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
                                      String updatedBy, OffsetDateTime updatedAt,
                                      boolean allowsDeploymentCredentials) {
        public boolean configured() {
            return source != CredentialSource.NONE;
        }
    }

    private final ConnectorAppCredentialRepository repository;
    private final CredentialService credentialService;
    private final Environment environment;
    private final ProjectSecurityService projectSecurityService;

    public ConnectorAppCredentialService(ConnectorAppCredentialRepository repository,
                                         CredentialService credentialService,
                                         Environment environment,
                                         ProjectSecurityService projectSecurityService) {
        this.repository = repository;
        this.credentialService = credentialService;
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
                    row.getClientId(), decryptClientSecret(row), List.of());
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
        row.setClientSecretEncrypted(credentialService.encryptSecret(row, clientSecret));
        row.setClientSecretLast4(last4(clientSecret));
        row.setUpdatedBy(caller.getId());
        repository.save(row);
        log.info("Connector app credential set for project={} connector={} by user={}",
                projectId, connectorId, caller.getId());
    }

    /**
     * Drops this project's own app credentials for a connector. ADMIN only, and a no-op when the
     * project never had a row.
     *
     * <p>What the connector falls back to depends on whether it
     * {@link OAuth2Connector#allowsDeploymentCredentials() takes} deployment credentials: the Google
     * family returns to the deployment env vars, while a publishing platform simply becomes
     * unconfigured and nobody can connect it until an admin enters another app.
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

    /**
     * The deployment leg of {@link #resolve}, or a bare {@link CredentialSource#NONE} for a connector
     * whose app must belong to the workspace. The empty {@code missingProperties} in that case is the
     * signal, not an oversight: naming an env var would tell an admin to do something that would not
     * help, since nothing reads it for this connector.
     */
    private ResolvedAppCredentials resolveFromDeployment(OAuth2Connector connector) {
        if (!connector.allowsDeploymentCredentials()) {
            return new ResolvedAppCredentials(connector.getId(), CredentialSource.NONE, null, null, List.of());
        }
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
            if (isPreEnvelope(row)) {
                log.error("Connector app credential project={} connector={} predates envelope encryption; "
                        + "its secret cannot be read and an admin must re-enter it",
                        row.getProjectId(), row.getConnectorId());
            }
            return new AppCredentialStatus(connector.getId(), CredentialSource.PROJECT, row.getClientId(),
                    row.getClientSecretLast4(), List.of(), row.getUpdatedBy(), row.getUpdatedAt(),
                    connector.allowsDeploymentCredentials());
        }
        ResolvedAppCredentials deployment = resolveFromDeployment(connector);
        return new AppCredentialStatus(connector.getId(), deployment.source(), deployment.clientId(),
                last4(deployment.clientSecret()), deployment.missingProperties(), null, null,
                connector.allowsDeploymentCredentials());
    }

    /**
     * The stored secret, opened with this row's own DEK.
     *
     * <p>A row with no {@code kms_key_reference} was written before this table joined the envelope
     * (Flyway V118 encrypted it under the single deployment-wide workflow-secrets key). Its ciphertext
     * is not openable here, and the envelope would answer with null rather than an error — which for a
     * client secret means silently sending an OAuth provider the wrong credentials. So this refuses,
     * and names the one thing that fixes it. Only a developer database can hold such a row: V118 has
     * never shipped.
     */
    private String decryptClientSecret(ConnectorAppCredential row) {
        if (isPreEnvelope(row)) {
            throw new BusinessException("The app credentials stored for connector '" + row.getConnectorId()
                    + "' predate envelope encryption and can no longer be decrypted. A project admin must "
                    + "re-enter the client secret in Settings -> Integrations.");
        }
        return credentialService.decryptSecret(row, row.getClientSecretEncrypted());
    }

    private static boolean isPreEnvelope(ConnectorAppCredential row) {
        return isBlank(row.getKmsKeyReference());
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
