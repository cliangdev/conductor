package com.conductor.service;

import com.conductor.entity.ConnectorAppCredential;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.integration.OAuth2Connector;
import com.conductor.integration.OAuth2Connector.AppOwnership;
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
 * Resolves the OAuth <em>app</em> credentials a connector authenticates as, per project, according to
 * the connector's {@link OAuth2Connector.AppOwnership}.
 *
 * <p>For a {@link OAuth2Connector.AppOwnership#WORKSPACE_OR_DEPLOYMENT} connector (the Google family)
 * resolution order is fixed and deliberately additive:
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
 * <p><b>{@link OAuth2Connector.AppOwnership#WORKSPACE_ONLY} (YouTube) never reads the environment.</b>
 * Google verifies {@code youtube.upload} against one OAuth client and meters upload quota against that
 * client's Google Cloud project, so a shared deployment app would mean every workspace draining the
 * same quota. No row means {@link CredentialSource#NONE} with <em>no</em> missing-property names, which
 * is how a caller tells "this workspace has entered nothing" apart from "the deployment is missing an
 * env var".
 *
 * <p><b>{@link OAuth2Connector.AppOwnership#DEPLOYMENT_ONLY} (Meta, TikTok) never reads a project row,
 * even if one is present.</b> Conductor registers one app per platform, carries it through that
 * platform's app review once, and every workspace authorizes through it: there is no per-project app
 * to bring, so {@link #put}/{@link #clear} refuse outright and {@link #resolve}/{@link #status} do not
 * even look for a row. Skipping the lookup rather than merely ignoring its result matters: a row can be
 * left over from before a connector's ownership changed (see {@code V137__central_platform_apps.sql}),
 * and a stale row must never quietly win a resolve. The masked {@link AppCredentialStatus} for this
 * ownership also nulls out {@code clientId}/{@code clientSecretLast4} (see {@link #toStatus}) because
 * the endpoints that read it are member-level, not admin, and would otherwise hand every member of
 * every tenant Conductor's own app id and four characters of its secret.
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
     *
     * <p>{@code clientId}/{@code clientSecretLast4} are also null whenever {@code appOwnership} is
     * {@link AppOwnership#DEPLOYMENT_ONLY}: see the class javadoc for why exposing even four
     * characters of Conductor's own app secret to every project member would be a mistake.
     */
    public record AppCredentialStatus(String connectorId, CredentialSource source, String clientId,
                                      String clientSecretLast4, List<String> missingProperties,
                                      String updatedBy, OffsetDateTime updatedAt,
                                      AppOwnership appOwnership) {
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
     *
     * <p>{@link AppOwnership#DEPLOYMENT_ONLY} never looks for a project row at all: the lookup is
     * skipped, not merely ignored, so a row left over from before this connector's ownership changed
     * can never win a resolve.
     */
    public ResolvedAppCredentials resolve(String projectId, OAuth2Connector connector) {
        if (connector.appOwnership() == AppOwnership.DEPLOYMENT_ONLY) {
            return resolveFromDeployment(connector);
        }
        ConnectorAppCredential row = projectId == null ? null
                : repository.findByProjectIdAndConnectorId(projectId, connector.getId()).orElse(null);
        if (row != null) {
            return new ResolvedAppCredentials(connector.getId(), CredentialSource.PROJECT,
                    row.getClientId(), decryptClientSecret(row), List.of());
        }
        return resolveFromDeployment(connector);
    }

    /**
     * Masked counterpart to {@link #resolve}, for anything that only needs presence and provenance.
     * Mirrors {@link #resolve}'s refusal to look up a project row for a
     * {@link AppOwnership#DEPLOYMENT_ONLY} connector.
     */
    public AppCredentialStatus status(String projectId, OAuth2Connector connector) {
        if (connector.appOwnership() == AppOwnership.DEPLOYMENT_ONLY) {
            return toStatus(connector, null);
        }
        ConnectorAppCredential row = projectId == null ? null
                : repository.findByProjectIdAndConnectorId(projectId, connector.getId()).orElse(null);
        return toStatus(connector, row);
    }

    /**
     * {@link #status} for a whole catalog in one query rather than one per connector — the shape the
     * connector list needs. A row is looked up for every connector in one pass (cheaper than a
     * per-connector branch), but a {@link AppOwnership#DEPLOYMENT_ONLY} connector's row, if a stale one
     * exists, is discarded before it ever reaches {@link #toStatus}, the same rule as {@link #resolve},
     * just applied after a shared query instead of by skipping it.
     */
    public List<AppCredentialStatus> statuses(String projectId, Collection<? extends OAuth2Connector> connectors) {
        Map<String, ConnectorAppCredential> byConnector = projectId == null ? Map.of()
                : repository.findByProjectId(projectId).stream()
                        .collect(Collectors.toMap(ConnectorAppCredential::getConnectorId, row -> row));
        return connectors.stream()
                .map(connector -> toStatus(connector, connector.appOwnership() == AppOwnership.DEPLOYMENT_ONLY
                        ? null : byConnector.get(connector.getId())))
                .toList();
    }

    /**
     * Stores (or replaces) this project's own app credentials for a connector. ADMIN only: these
     * credentials decide which platform application every member's consent flow runs as.
     *
     * <p>Refuses for a {@link AppOwnership#DEPLOYMENT_ONLY} connector: Conductor publishes through its
     * own reviewed app for that platform, and there is nothing for a project to configure.
     */
    @Transactional
    public void put(String projectId, OAuth2Connector connector, String clientId, String clientSecret, User caller) {
        requireProjectAdmin(projectId, caller);
        requireNotDeploymentOnly(connector);
        if (clientId == null || clientId.isBlank()) {
            throw new BusinessException("clientId is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new BusinessException("clientSecret is required");
        }
        String connectorId = connector.getId();
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
     * <p>What the connector falls back to depends on its {@link AppOwnership}: a
     * {@link AppOwnership#WORKSPACE_OR_DEPLOYMENT} connector (the Google family) returns to the
     * deployment env vars, while a {@link AppOwnership#WORKSPACE_ONLY} connector simply becomes
     * unconfigured and nobody can connect it until an admin enters another app.
     *
     * <p>Refuses for a {@link AppOwnership#DEPLOYMENT_ONLY} connector, for the same reason {@link #put}
     * does: there is no project-owned app to clear.
     */
    @Transactional
    public void clear(String projectId, OAuth2Connector connector, User caller) {
        requireProjectAdmin(projectId, caller);
        requireNotDeploymentOnly(connector);
        String connectorId = connector.getId();
        repository.findByProjectIdAndConnectorId(projectId, connectorId).ifPresent(row -> {
            repository.delete(row);
            log.info("Connector app credential cleared for project={} connector={} by user={}",
                    projectId, connectorId, caller.getId());
        });
    }

    /**
     * Shared refusal for {@link #put}/{@link #clear} against a {@link AppOwnership#DEPLOYMENT_ONLY}
     * connector. Message is deliberately about the platform, not the API: an admin who reaches this
     * clicked something the UI should not have offered, not something that needs an env var or a
     * workaround.
     */
    private static void requireNotDeploymentOnly(OAuth2Connector connector) {
        if (connector.appOwnership() == AppOwnership.DEPLOYMENT_ONLY) {
            throw new BusinessException("Conductor publishes through its own reviewed app for '"
                    + connector.getId() + "'. There is nothing to configure here.");
        }
    }

    /**
     * The deployment leg of {@link #resolve}: reads the environment for both
     * {@link AppOwnership#DEPLOYMENT_ONLY} (the whole story for that ownership) and
     * {@link AppOwnership#WORKSPACE_OR_DEPLOYMENT} (the fallback once no project row exists). For
     * {@link AppOwnership#WORKSPACE_ONLY} this is a bare {@link CredentialSource#NONE} with no
     * missing-property names: the signal, not an oversight, since naming an env var would tell an admin to
     * do something that would not help, since nothing reads it for this connector.
     */
    private ResolvedAppCredentials resolveFromDeployment(OAuth2Connector connector) {
        if (connector.appOwnership() == AppOwnership.WORKSPACE_ONLY) {
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

    /**
     * Builds the masked view for one connector. {@code row} is null both for "no project row exists"
     * and, deliberately, for every {@link AppOwnership#DEPLOYMENT_ONLY} connector regardless of what
     * the table holds; see {@link #resolve} and {@link #statuses}.
     */
    private AppCredentialStatus toStatus(OAuth2Connector connector, ConnectorAppCredential row) {
        if (row != null) {
            if (isPreEnvelope(row)) {
                log.error("Connector app credential project={} connector={} predates envelope encryption; "
                        + "its secret cannot be read and an admin must re-enter it",
                        row.getProjectId(), row.getConnectorId());
            }
            return new AppCredentialStatus(connector.getId(), CredentialSource.PROJECT, row.getClientId(),
                    row.getClientSecretLast4(), List.of(), row.getUpdatedBy(), row.getUpdatedAt(),
                    connector.appOwnership());
        }
        ResolvedAppCredentials deployment = resolveFromDeployment(connector);
        // The integrations list and catalog endpoints this feeds are member-level, not admin, so a
        // DEPLOYMENT_ONLY connector's id and last4 must never leave this method: every member of every
        // tenant can reach this status, and those four characters would expose Conductor's own app.
        boolean exposeIdentity = connector.appOwnership() != AppOwnership.DEPLOYMENT_ONLY;
        return new AppCredentialStatus(connector.getId(), deployment.source(),
                exposeIdentity ? deployment.clientId() : null,
                exposeIdentity ? last4(deployment.clientSecret()) : null,
                deployment.missingProperties(), null, null, connector.appOwnership());
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
