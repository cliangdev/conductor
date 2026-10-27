package com.conductor.scheduling;

import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Verifies the Google-signed OIDC ID token Cloud Scheduler attaches to its HTTP target: signature (Google's
 * certs, cached by {@link TokenVerifier}), issuer, audience, expiry, then the caller's {@code email} against
 * the configured invoker service account with {@code email_verified}. Fails closed: if the audience or invoker
 * email is not configured, nothing verifies. Never logs the token.
 */
@Component
public class SchedulerTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTokenVerifier.class);
    static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final String invokerEmail;
    private final List<TokenVerifier> verifiers;

    @Autowired
    public SchedulerTokenVerifier(@Value("${conductor.scheduling.oidc-audience:}") String audience,
                                  @Value("${conductor.scheduling.invoker-email:}") String invokerEmail) {
        this(audience, invokerEmail, TokenVerifier::newBuilder);
    }

    /** The builder supplier is the seam tests use to pin a public key and clock instead of fetching Google's certs. */
    SchedulerTokenVerifier(String audience, String invokerEmail, Supplier<TokenVerifier.Builder> builders) {
        this.invokerEmail = invokerEmail == null ? "" : invokerEmail.trim();
        String aud = audience == null ? "" : audience.trim();
        if (aud.isEmpty() || this.invokerEmail.isEmpty()) {
            this.verifiers = List.of();
            log.warn("Scheduler OIDC audience or invoker email is not configured; "
                    + "/internal/v1/scheduler/jobs/* will reject every call");
        } else {
            this.verifiers = ISSUERS.stream()
                    .map(issuer -> builders.get().setAudience(aud).setIssuer(issuer).build())
                    .toList();
        }
    }

    /** The verified caller email, or empty when the token is missing, invalid, or not from the configured invoker. */
    public Optional<String> verify(String bearerToken) {
        if (verifiers.isEmpty() || bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        for (TokenVerifier verifier : verifiers) {
            try {
                JsonWebToken.Payload payload = verifier.verify(bearerToken).getPayload();
                Object email = payload.get("email");
                Object verified = payload.get("email_verified");
                if (email instanceof String e && e.equalsIgnoreCase(invokerEmail) && Boolean.TRUE.equals(verified)) {
                    return Optional.of(e);
                }
                return Optional.empty();
            } catch (TokenVerifier.VerificationException e) {
                // wrong issuer for this verifier, bad signature, expired, wrong audience: try the next issuer
            } catch (RuntimeException e) {
                log.warn("Scheduler token verification failed unexpectedly: {}", e.toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
