package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Revokes the provider-side OAuth grant when a member disconnects a connection, so the app also
 * disappears from the user's account connections (for Google: myaccount.google.com, Third-party
 * access). YouTube Developer Policies and Google's OAuth review expect this. Only connectors that
 * declare {@link OAuth2Connector#revocationUrl()} are affected (YouTube today).
 *
 * <p><b>Revocation is per grant, not per token.</b> Revoking a Google refresh token invalidates the
 * whole grant for that user and OAuth client, including every other token issued to that user for this
 * client. Two consequences shape the callers:
 * <ul>
 *   <li><b>Never on a duplicate merge.</b> The survivor of a merge holds a token from the same grant;
 *       revoking the duplicate's token would kill the survivor too. Only
 *       {@link ConnectionDisconnectService#disconnect} calls this.</li>
 *   <li><b>Same-account guard.</b> Another connection, in any workspace, to the same external account
 *       (YouTube: the same channel id) holds a token from the same grant, so revoking would silently
 *       break that workspace. If one exists the revoke is skipped and logged.</li>
 * </ul>
 *
 * <p><b>Order and failure.</b> The token is read before the row is deleted, but the HTTP call runs only
 * after the surrounding transaction commits, so a rolled-back disconnect revokes nothing (with no
 * transaction active it runs immediately). It is best-effort: a network error or 5xx logs a warning
 * (connector, connection id, status; never the token) and never fails the disconnect. A 400 means the
 * token was already revoked or expired, which is the outcome we wanted, and logs at INFO.
 */
@Service
public class OAuthRevocationService {

    private static final Logger log = LoggerFactory.getLogger(OAuthRevocationService.class);

    private final ConnectionService connectionService;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRegistry connectorRegistry;
    private final RestTemplate restTemplate;

    @Autowired
    public OAuthRevocationService(ConnectionService connectionService,
                                  ConnectionRepository connectionRepository,
                                  ConnectorRegistry connectorRegistry) {
        this(connectionService, connectionRepository, connectorRegistry, new RestTemplate());
    }

    /** Test seam: lets a test supply a mocked or bound {@link RestTemplate}. */
    OAuthRevocationService(ConnectionService connectionService,
                           ConnectionRepository connectionRepository,
                           ConnectorRegistry connectorRegistry,
                           RestTemplate restTemplate) {
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.connectorRegistry = connectorRegistry;
        this.restTemplate = restTemplate;
    }

    /**
     * Schedules revocation of {@code conn}'s grant for after the current transaction commits. Call it
     * before the row is deleted (the token is read here) and only from a real disconnect. Never throws.
     */
    public void revokeAfterCommit(Connection conn) {
        try {
            prepare(conn).ifPresent(this::runAfterCommit);
        } catch (RuntimeException e) {
            log.warn("Could not prepare grant revocation for connection={} connector={}: {}",
                    conn.getId(), conn.getConnectorId(), e.getClass().getSimpleName());
        }
    }

    private record Revocation(String connectorId, String connectionId, String url, String token) {}

    private Optional<Revocation> prepare(Connection conn) {
        Optional<OAuth2Connector> found = connectorRegistry.findOAuth2(conn.getConnectorId());
        if (found.isEmpty() || found.get().revocationUrl().isEmpty()) {
            return Optional.empty();
        }
        OAuth2Connector connector = found.get();
        DecryptedCredentials creds = connectionService.decrypt(conn);
        String token = creds.refreshToken() != null && !creds.refreshToken().isBlank()
                ? creds.refreshToken() : creds.accessToken();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<String> key = connector.accountIdentityConfigKey();
        if (key.isEmpty()) {
            log.warn("Kept {} grant for connection={}: connector declares no account identity key",
                    conn.getConnectorId(), conn.getId());
            return Optional.empty();
        }
        Map<String, Object> config = creds.configJson();
        Object account = config == null ? null : config.get(key.get());
        if (account instanceof String id && !id.isBlank()) {
            Optional<Connection> other = connectionRepository
                    .findByConnectorIdAndConfigValue(conn.getConnectorId(), key.get(), id).stream()
                    .filter(c -> !c.getId().equals(conn.getId()))
                    .findFirst();
            if (other.isPresent()) {
                log.info("kept Google grant: channel {} still connected in connection {}",
                        id, other.get().getId());
                return Optional.empty();
            }
        } else {
            log.warn("Kept {} grant for connection={}: no account id in its config",
                    conn.getConnectorId(), conn.getId());
            return Optional.empty();
        }
        return Optional.of(new Revocation(conn.getConnectorId(), conn.getId(),
                connector.revocationUrl().get(), token));
    }

    private void runAfterCommit(Revocation revocation) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revoke(revocation);
                }
            });
        } else {
            revoke(revocation);
        }
    }

    private void revoke(Revocation r) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", r.token());
        try {
            restTemplate.exchange(r.url(), HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
            log.info("Revoked {} grant for disconnected connection={}", r.connectorId(), r.connectionId());
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 400) {
                log.info("{} grant for connection={} was already revoked or expired (400)",
                        r.connectorId(), r.connectionId());
            } else {
                log.warn("Revoking {} grant for connection={} failed: HTTP {}",
                        r.connectorId(), r.connectionId(), e.getStatusCode().value());
            }
        } catch (RuntimeException e) {
            log.warn("Revoking {} grant for connection={} failed: {}",
                    r.connectorId(), r.connectionId(), e.getClass().getSimpleName());
        }
    }
}
