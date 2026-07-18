package com.conductor.agent.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.conductor.service.ProviderVerificationService.Check;
import com.conductor.service.ProviderVerificationService.CheckStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Real, minimal probe against the Anthropic Messages API for a {@code claude} BYO API key —
 * {@link com.conductor.service.ProviderVerificationService}'s only source of truth for whether the key
 * actually works (a stored row alone proves nothing). Deliberately builds a fresh {@link AnthropicClient}
 * per call rather than reusing {@link ClaudeProvider}'s per-key cache: a preflight must never leave a
 * pooled client behind for a key that might immediately be replaced or deleted, and its short
 * timeout/no-retry settings are specific to "prove reachability fast", not "run a real request".
 *
 * <p>The API key is held only in the local variable passed to {@link #check}; it is never logged or
 * placed on any {@link Check#message()}.
 */
@Component
public class ClaudeApiPreflight {

    /** Cheapest current model — this call exists to prove auth + reachability, not capability. */
    private static final String PROBE_MODEL = "claude-haiku-4-5";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final String CHECK_NAME = "anthropic-api";

    private final Function<String, AnthropicClient> clientFactory;

    public ClaudeApiPreflight() {
        this(apiKey -> AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(PROBE_TIMEOUT)
                .maxRetries(0)
                .build());
    }

    /** Test seam: injects a stub client factory so tests never hit the real Anthropic API. */
    ClaudeApiPreflight(Function<String, AnthropicClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Sends a one-token "ping" completion with {@code apiKey} and maps the outcome to a single check.
     * A 429 still proves the key is valid (Anthropic only rate-limits authenticated requests), so it
     * is reported as a warn, not a fail — it must never flip the overall verification to "error".
     */
    public List<Check> check(String apiKey) {
        AnthropicClient client = clientFactory.apply(apiKey);
        MessageCreateParams params = MessageCreateParams.builder()
                .model(PROBE_MODEL)
                .maxTokens(1)
                .addUserMessage("ping")
                .build();
        try {
            client.messages().create(params);
            return List.of(pass("Anthropic accepted a live test request — the API key is valid"));
        } catch (UnauthorizedException e) {
            return List.of(fail("Anthropic rejected the API key (401 unauthorized) — check the key and re-enter it"));
        } catch (PermissionDeniedException e) {
            return List.of(fail("The API key is valid but lacks permission for this request (403 forbidden)"));
        } catch (BadRequestException e) {
            return List.of(fail(billingAware(e)));
        } catch (RateLimitException e) {
            return List.of(new Check(CHECK_NAME, CheckStatus.WARN,
                    "Anthropic rate-limited the verification request (429) — the API key is valid but "
                            + "currently throttled"));
        } catch (InternalServerException e) {
            return List.of(fail("Could not reach Anthropic (server error) — try verifying again shortly"));
        } catch (AnthropicIoException e) {
            return List.of(fail("Could not reach Anthropic (network error or timeout) — try verifying again shortly"));
        } catch (AnthropicServiceException e) {
            return List.of(fail("Anthropic request failed (HTTP " + e.statusCode() + ")"));
        } catch (RuntimeException e) {
            return List.of(fail("Could not reach Anthropic: " + e.getClass().getSimpleName()));
        }
    }

    private String billingAware(BadRequestException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("credit") || lower.contains("billing") || lower.contains("balance")) {
            return "Anthropic account billing issue — " + message;
        }
        return "Anthropic rejected the request (400 bad request): " + message;
    }

    private Check pass(String message) {
        return new Check(CHECK_NAME, CheckStatus.PASS, message);
    }

    private Check fail(String message) {
        return new Check(CHECK_NAME, CheckStatus.FAIL, message);
    }
}
