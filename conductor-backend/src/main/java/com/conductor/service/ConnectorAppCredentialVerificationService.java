package com.conductor.service;

import com.conductor.integration.OAuth2Connector;
import com.conductor.service.ConnectorAppCredentialService.ResolvedAppCredentials;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proves a connector's OAuth <em>app</em> credentials actually work, by making a live call to the
 * provider with them — the counterpart, for platform apps, of what
 * {@link ProviderVerificationService} does for BYO model keys, and shaped the same way
 * ({@code VerificationReport(…, status, checkedAt, checks[])}).
 *
 * <p>Deliberately NOT {@code @Transactional}: every probe is a slow external call and must not hold
 * a DB connection across it — same convention as {@link ProviderVerificationService}. Nothing is
 * persisted; a stored "verified" flag would go stale the moment the platform app is rotated on the
 * provider's side, and the whole point of this action is that it is run on demand.
 *
 * <p><b>Three outcomes, not two.</b> {@link ReportStatus#VERIFIED} means the provider itself
 * accepted the pair; {@link ReportStatus#ERROR} means it rejected them; {@link ReportStatus#UNKNOWN}
 * means the probe could not tell. A network failure, a 5xx, or a response the probe does not
 * recognise all land in UNKNOWN — treating "we could not reach the provider" as a pass would put a
 * green badge on a credential nobody has verified.
 *
 * <p><b>The secret never leaves.</b> It is read into a local, sent to the provider, and every check
 * message is passed through {@link #redact} before it is returned, so even a provider that echoes
 * the secret back in its own error description cannot leak it into a response body.
 */
@Service
public class ConnectorAppCredentialVerificationService {

    /**
     * Meta is the one provider with a documented credentials-only grant:
     * {@code GET /oauth/access_token?grant_type=client_credentials} issues an app access token,
     * which only a valid app id/secret pair can produce. Every other connector falls back to the
     * expected-failure probe below.
     */
    private static final String META_CONNECTOR_ID = "meta";

    private static final String CHECK_NAME = "oauth-app-credentials";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * A refresh token no provider can ever have issued. The expected-failure probe sends it on
     * purpose: the request must fail, and <em>which</em> failure comes back is the signal.
     */
    private static final String IMPOSSIBLE_REFRESH_TOKEN = "conductor-app-credential-probe-not-a-real-token";

    /** OAuth2 error codes that indict the client credentials themselves (RFC 6749 §5.2). */
    private static final List<String> CLIENT_ERRORS = List.of("invalid_client", "unauthorized_client");
    /** The code that means the credentials passed and only the bogus grant was rejected. */
    private static final String GRANT_ERROR = "invalid_grant";

    /** A report's overall outcome. */
    public enum ReportStatus {
        VERIFIED, ERROR, UNKNOWN;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record VerificationReport(String connectorId, ReportStatus status, OffsetDateTime checkedAt,
                                     List<Check> checks) {}

    private final ConnectorAppCredentialService appCredentialService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** {@code @Autowired} names the production constructor: with two, Spring cannot pick one on its own. */
    @Autowired
    public ConnectorAppCredentialVerificationService(ConnectorAppCredentialService appCredentialService,
                                                     ObjectMapper objectMapper) {
        this(appCredentialService, objectMapper, probeRestTemplate());
    }

    /** A probe must fail fast rather than hang a request thread on an unresponsive provider. */
    private static RestTemplate probeRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_TIMEOUT);
        factory.setReadTimeout(PROBE_TIMEOUT);
        return new RestTemplate(factory);
    }

    /** Test seam: injects a {@link RestTemplate} so tests never call a real provider. */
    ConnectorAppCredentialVerificationService(ConnectorAppCredentialService appCredentialService,
                                              ObjectMapper objectMapper,
                                              RestTemplate restTemplate) {
        this.appCredentialService = appCredentialService;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /** Probes {@code connector}'s effective credentials for {@code projectId} and reports what it proved. */
    public VerificationReport verify(String projectId, OAuth2Connector connector) {
        ResolvedAppCredentials credentials;
        try {
            credentials = appCredentialService.resolve(projectId, connector);
        } catch (RuntimeException e) {
            // Naming the exception class only: its message could carry ciphertext or request detail.
            return report(connector, null, List.of(fail("Could not read the stored app credentials ("
                    + e.getClass().getSimpleName() + ") — re-enter them and try again")));
        }
        List<Check> checks;
        try {
            checks = credentials.configured() ? probe(connector, credentials) : notConfigured(credentials);
        } catch (RuntimeException e) {
            checks = List.of(indeterminate("The verification probe failed unexpectedly ("
                    + e.getClass().getSimpleName() + "), so it could not determine whether the "
                    + "credentials are valid"));
        }
        return report(connector, credentials.clientSecret(), checks);
    }

    private VerificationReport report(OAuth2Connector connector, String secret, List<Check> checks) {
        List<Check> safe = checks.stream()
                .map(check -> new Check(check.name(), check.status(), redact(check.message(), secret)))
                .toList();
        return new VerificationReport(connector.getId(), overall(safe), OffsetDateTime.now(), safe);
    }

    private List<Check> probe(OAuth2Connector connector, ResolvedAppCredentials credentials) {
        return META_CONNECTOR_ID.equals(connector.getId())
                ? probeAppAccessToken(connector, credentials)
                : probeExpectedFailure(connector, credentials);
    }

    /**
     * The "nothing to probe" report. A connector that takes no deployment credentials has no env var
     * worth naming — suggesting one would send an admin to change something nothing reads — so it is
     * told the single thing that fixes it.
     */
    private List<Check> notConfigured(ResolvedAppCredentials credentials) {
        if (credentials.missingProperties().isEmpty()) {
            return List.of(fail("No app credentials are configured for this connector — enter this "
                    + "workspace's client id and secret above, then verify again"));
        }
        return List.of(fail("No app credentials are configured for this connector — set them for this "
                + "workspace, or set " + String.join(" and ", credentials.missingProperties())
                + " on the deployment"));
    }

    /**
     * Meta's {@code client_credentials} grant. A returned app access token is direct proof the app id
     * and secret are a valid pair; Meta's own {@code error.message} is the reason when they are not.
     */
    private List<Check> probeAppAccessToken(OAuth2Connector connector, ResolvedAppCredentials credentials) {
        String provider = providerName(connector);
        URI uri = UriComponentsBuilder.fromUriString(connector.tokenUrl())
                .queryParam("grant_type", "client_credentials")
                .queryParam("client_id", credentials.clientId())
                .queryParam("client_secret", credentials.clientSecret())
                .encode()
                .build()
                .toUri();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            Object token = parse(response.getBody()).get("access_token");
            if (token instanceof String value && !value.isBlank()) {
                return List.of(pass(provider + " issued an app access token for this app id and secret — "
                        + "the credentials are valid"));
            }
            return List.of(indeterminate(provider + " answered without an app access token, so this probe "
                    + "could not determine whether the credentials are valid"));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                return List.of(indeterminate(provider + " returned a server error (HTTP "
                        + e.getStatusCode().value() + "), so this probe could not determine whether the "
                        + "credentials are valid"));
            }
            String reason = metaErrorMessage(e.getResponseBodyAsString());
            if (reason == null) {
                return List.of(indeterminate(provider + " rejected the probe with an unrecognised response "
                        + "(HTTP " + e.getStatusCode().value() + "), so this probe could not determine "
                        + "whether the credentials are valid"));
            }
            return List.of(fail(provider + " rejected the app credentials: " + reason));
        } catch (RestClientException e) {
            return List.of(unreachable(provider));
        }
    }

    /**
     * The probe for providers with no credentials-only grant (Google, TikTok): send a token request
     * that is <em>expected</em> to fail, with a refresh token that cannot be real, and read which
     * failure came back. {@code invalid_client} indicts the credentials; {@code invalid_grant} means
     * the provider authenticated the app and rejected only the bogus grant — which is exactly the
     * evidence wanted, and is said out loud in the check message.
     */
    private List<Check> probeExpectedFailure(OAuth2Connector connector, ResolvedAppCredentials credentials) {
        String provider = providerName(connector);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", IMPOSSIBLE_REFRESH_TOKEN);
        form.add(connector.clientIdParamName(), credentials.clientId());
        form.add("client_secret", credentials.clientSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            restTemplate.postForEntity(connector.tokenUrl(), new HttpEntity<>(form, headers), String.class);
            // A deliberately impossible grant must not succeed; something answered that isn't the token
            // endpoint this probe reasons about.
            return List.of(indeterminate(provider + " accepted a grant that cannot be valid, so this probe "
                    + "could not determine whether the credentials are valid"));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                return List.of(indeterminate(provider + " returned a server error (HTTP "
                        + e.getStatusCode().value() + "), so this probe could not determine whether the "
                        + "credentials are valid"));
            }
            return List.of(classifyOAuthError(provider, parse(e.getResponseBodyAsString()),
                    e.getStatusCode().value()));
        } catch (RestClientException e) {
            return List.of(unreachable(provider));
        }
    }

    private Check classifyOAuthError(String provider, Map<String, Object> body, int statusCode) {
        String error = body.get("error") instanceof String value ? value.toLowerCase(Locale.ROOT) : null;
        if (error == null) {
            return indeterminate(provider + " rejected the probe with an unrecognised response (HTTP "
                    + statusCode + "), so this probe could not determine whether the credentials are valid");
        }
        if (CLIENT_ERRORS.contains(error)) {
            return fail(provider + " rejected the client credentials themselves (" + error
                    + ") — the client id or secret is wrong");
        }
        if (GRANT_ERROR.equals(error)) {
            return pass(provider + " authenticated this client id and secret and rejected only the "
                    + "deliberately invalid grant this probe sent (" + error + "). That proves the app "
                    + "credentials, not that any particular connection or token works");
        }
        return indeterminate(provider + " answered '" + error + "', which proves neither that the "
                + "credentials are valid nor that they are wrong");
    }

    /** Meta reports failures as {@code {"error": {"message": …, "type": …, "code": …}}}. */
    private String metaErrorMessage(String body) {
        Object error = parse(body).get("error");
        if (error instanceof Map<?, ?> map && map.get("message") instanceof String message) {
            return message;
        }
        return error instanceof String message ? message : null;
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) parsed;
            return typed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static ReportStatus overall(List<Check> checks) {
        if (checks.stream().anyMatch(c -> c.status() == CheckStatus.FAIL)) {
            return ReportStatus.ERROR;
        }
        return checks.stream().anyMatch(c -> c.status() == CheckStatus.WARN)
                ? ReportStatus.UNKNOWN : ReportStatus.VERIFIED;
    }

    /** Last line of defence for the never-echo-the-secret rule. */
    private static String redact(String message, String secret) {
        if (message == null || secret == null || secret.isBlank()) {
            return message;
        }
        return message.replace(secret, "***");
    }

    private String providerName(OAuth2Connector connector) {
        return connector.getMetadata().name();
    }

    private Check unreachable(String provider) {
        return indeterminate("Could not reach " + provider + " (network error or timeout), so this probe "
                + "could not determine whether the credentials are valid — try again shortly");
    }

    private static Check pass(String message) {
        return new Check(CHECK_NAME, CheckStatus.PASS, message);
    }

    private static Check fail(String message) {
        return new Check(CHECK_NAME, CheckStatus.FAIL, message);
    }

    private static Check indeterminate(String message) {
        return new Check(CHECK_NAME, CheckStatus.WARN, message);
    }
}
