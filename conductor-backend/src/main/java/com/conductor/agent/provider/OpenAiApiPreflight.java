package com.conductor.agent.provider;

import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Real, minimal probe against the OpenAI Chat Completions API for an {@code openai} BYO API key —
 * mirrors {@link ClaudeApiPreflight} closely (same fresh-client-per-call rationale, same exception-to-check
 * mapping shape) so the two adapters stay easy to compare. Deliberately builds a fresh {@link OpenAIClient}
 * per call rather than reusing {@link OpenAiProvider}'s per-key cache: a preflight must never leave a
 * pooled client behind for a key that might immediately be replaced or deleted, and its short
 * timeout/no-retry settings are specific to "prove reachability fast", not "run a real request".
 *
 * <p>v1 deliberately does NOT retry with {@link OpenAiProvider#FALLBACK_MODEL} on a model-not-found
 * response. {@link #PROBE_MODEL} is the cheapest current-generation model and is broadly available to
 * any account with standard Chat Completions access; a preflight's job is to surface a real problem
 * (including "this key can't reach this model"), not to paper over one with a second guess. If this ever
 * proves too strict in practice, add one bounded retry here — not a general retry loop.
 *
 * <p>The API key is held only in the local variable passed to {@link #check}; it is never logged or
 * placed on any {@link Check#message()}.
 */
@Component
public class OpenAiApiPreflight {

    /** Cheapest current model — this call exists to prove auth + reachability, not capability. */
    private static final String PROBE_MODEL = "gpt-5.4-nano";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final String CHECK_NAME = "openai-api";

    private final Function<String, OpenAIClient> clientFactory;

    public OpenAiApiPreflight() {
        this(apiKey -> OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(PROBE_TIMEOUT)
                .maxRetries(0)
                .build());
    }

    /** Test seam: injects a stub client factory so tests never hit the real OpenAI API. */
    OpenAiApiPreflight(Function<String, OpenAIClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Sends a one-word "ping" completion with {@code apiKey} and maps the outcome to a single check. A
     * 429 usually still proves the key is valid (OpenAI only rate-limits authenticated requests), so
     * ordinary throttling is a warn, not a fail — it must never flip the overall verification to "error".
     * OpenAI also returns 429 for hard quota exhaustion (no credits / billing not set up), which a key
     * can never recover from by itself — {@link #isQuotaExhausted} tells the two apart and quota
     * exhaustion is reported as a fail instead.
     */
    public List<Check> check(String apiKey) {
        OpenAIClient client = clientFactory.apply(apiKey);
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(PROBE_MODEL)
                .maxCompletionTokens(1)
                .addUserMessage("ping")
                .build();
        try {
            client.chat().completions().create(params);
            return List.of(pass("OpenAI accepted a live test request — the API key is valid"));
        } catch (UnauthorizedException e) {
            return List.of(fail("OpenAI rejected the API key (401 unauthorized) — check the key and re-enter it"));
        } catch (PermissionDeniedException e) {
            return List.of(fail("The API key is valid but lacks permission for this request (403 forbidden)"));
        } catch (BadRequestException e) {
            return List.of(fail(billingAware(e.getMessage())));
        } catch (RateLimitException e) {
            if (isQuotaExhausted(e)) {
                return List.of(fail(billingAware(e.getMessage())));
            }
            return List.of(new Check(CHECK_NAME, CheckStatus.WARN,
                    "OpenAI rate-limited the verification request (429) — the API key is valid but "
                            + "currently throttled"));
        } catch (InternalServerException e) {
            return List.of(fail("Could not reach OpenAI (server error) — try verifying again shortly"));
        } catch (OpenAIIoException e) {
            return List.of(fail("Could not reach OpenAI (network error or timeout) — try verifying again shortly"));
        } catch (OpenAIServiceException e) {
            return List.of(fail("OpenAI request failed (HTTP " + e.statusCode() + ")"));
        } catch (RuntimeException e) {
            // Not necessarily a reachability problem (e.g. a response-parsing failure) — name the
            // exception class only, never its message, which could embed request details.
            return List.of(fail("Verification failed unexpectedly (" + e.getClass().getSimpleName()
                    + ") — try again"));
        }
    }

    /**
     * True when a 429 is OpenAI reporting quota exhaustion rather than ordinary throttling. Checks the
     * structured {@code error.code}/{@code error.type} fields first (OpenAI sets both to
     * {@code insufficient_quota} for this case) and falls back to a message-substring check for
     * robustness if either field is absent.
     */
    private boolean isQuotaExhausted(RateLimitException e) {
        if (e.code().map(c -> c.toLowerCase(Locale.ROOT).contains("insufficient_quota")).orElse(false)) {
            return true;
        }
        if (e.type().map(t -> t.toLowerCase(Locale.ROOT).contains("insufficient_quota")).orElse(false)) {
            return true;
        }
        return isBillingRelated(e.getMessage());
    }

    private String billingAware(String message) {
        String safeMessage = message == null ? "" : message;
        if (isBillingRelated(safeMessage)) {
            return "OpenAI account billing issue — " + safeMessage;
        }
        return "OpenAI rejected the request (400 bad request): " + safeMessage;
    }

    private boolean isBillingRelated(String message) {
        String lower = (message == null ? "" : message).toLowerCase(Locale.ROOT);
        return lower.contains("insufficient_quota") || lower.contains("billing") || lower.contains("credit")
                || lower.contains("balance");
    }

    private Check pass(String message) {
        return new Check(CHECK_NAME, CheckStatus.PASS, message);
    }

    private Check fail(String message) {
        return new Check(CHECK_NAME, CheckStatus.FAIL, message);
    }
}
