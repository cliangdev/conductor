package com.conductor.agent.provider;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ErrorObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring, no real network call) for {@link OpenAiApiPreflight}'s exception-to-check
 * mapping. Uses the package-private client-factory constructor to inject a stub {@link OpenAIClient} whose
 * {@code chat().completions().create(...)} either succeeds or throws a specific SDK exception type, so
 * each branch of the mapping is exercised deterministically. Mirrors {@code ClaudeApiPreflightTest}'s
 * structure and its {@link ErrorObject}-building workaround: several {@code com.openai.errors.*} builders
 * have a fixed internal status code with no {@code .statusCode(int)} setter (only {@link
 * InternalServerException.Builder} exposes one) and none has a {@code .body(...)} setter, so a specific
 * error message/code is injected via {@code .error(ErrorObject)} instead.
 */
class OpenAiApiPreflightTest {

    private static final String SECRET_KEY = "sk-openai-super-secret-do-not-leak";

    @Test
    void check_success_returnsPass() {
        OpenAiApiPreflight preflight = preflightThatReturns(mock(ChatCompletion.class));

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.PASS);
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_unauthorized_returnsFail() {
        OpenAiApiPreflight preflight = preflightThatThrows(UnauthorizedException.builder().headers(emptyHeaders()).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("401");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_permissionDenied_returnsFail() {
        OpenAiApiPreflight preflight = preflightThatThrows(PermissionDeniedException.builder().headers(emptyHeaders()).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("403");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_billingBadRequest_returnsFailWithBillingMessage() {
        BadRequestException billing = BadRequestException.builder()
                .headers(emptyHeaders())
                .error(errorObject("insufficient_quota",
                        "You exceeded your current quota, please check your plan and billing details."))
                .build();
        OpenAiApiPreflight preflight = preflightThatThrows(billing);

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("billing");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_nonBillingBadRequest_returnsFailWithGenericMessage() {
        BadRequestException badRequest = BadRequestException.builder()
                .headers(emptyHeaders())
                .error(errorObject("invalid_request_error", "'max_completion_tokens' must be an integer"))
                .build();
        OpenAiApiPreflight preflight = preflightThatThrows(badRequest);

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("400");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_throttlingRateLimit_returnsWarnNotFail() {
        RateLimitException throttled = RateLimitException.builder()
                .headers(emptyHeaders())
                .error(errorObject("rate_limit_exceeded", "Rate limit reached for requests"))
                .build();
        OpenAiApiPreflight preflight = preflightThatThrows(throttled);

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        // Ordinary throttling proves the key IS valid (OpenAI only rate-limits authenticated requests) —
        // must never be a fail, or the overall verification would wrongly flip to "error" for a working key.
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.WARN);
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_quotaExhaustedRateLimit_returnsFailNotWarn() {
        // OpenAI also uses HTTP 429 for hard quota exhaustion (no credits / billing not configured) —
        // unlike throttling, a quota-exhausted key can never succeed on retry, so this must be a fail,
        // distinguished from ordinary throttling via the structured error.code field.
        RateLimitException quotaExhausted = RateLimitException.builder()
                .headers(emptyHeaders())
                .error(errorObject("insufficient_quota",
                        "You exceeded your current quota, please check your plan and billing details."))
                .build();
        OpenAiApiPreflight preflight = preflightThatThrows(quotaExhausted);

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("billing");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_internalServerError_returnsFailCouldNotReach() {
        OpenAiApiPreflight preflight = preflightThatThrows(
                InternalServerException.builder().statusCode(500).headers(emptyHeaders()).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("could not reach openai");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_timeoutOrNetworkError_returnsFailCouldNotReach() {
        OpenAiApiPreflight preflight = preflightThatThrows(new OpenAIIoException("timed out"));

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("could not reach openai");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_otherServiceException_returnsFailNamingHttpStatus() {
        // e.g. an account with no access to the probe model — falls through to the generic
        // OpenAIServiceException branch rather than a dedicated catch, per the class's exception mapping.
        OpenAiApiPreflight preflight = preflightThatThrows(NotFoundException.builder().headers(emptyHeaders()).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("404");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_unexpectedRuntimeException_namesClassOnlyNeverMessage() {
        String sensitiveDetail = "leaked-request-detail-should-never-appear";
        OpenAiApiPreflight preflight = preflightThatThrows(new IllegalStateException(sensitiveDetail));

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).contains("IllegalStateException");
        assertThat(checks.get(0).message()).doesNotContain(sensitiveDetail);
        assertKeyNeverLeaked(checks);
    }

    private ErrorObject errorObject(String code, String message) {
        return ErrorObject.builder()
                .code(code)
                .message(message)
                .param(Optional.<String>empty())
                .type(code)
                .build();
    }

    private OpenAiApiPreflight preflightThatReturns(ChatCompletion completion) {
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chatService = mock(ChatService.class);
        ChatCompletionService completionService = mock(ChatCompletionService.class);
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(completion);
        return new OpenAiApiPreflight(keyCapturingFactory(client));
    }

    private OpenAiApiPreflight preflightThatThrows(RuntimeException exception) {
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chatService = mock(ChatService.class);
        ChatCompletionService completionService = mock(ChatCompletionService.class);
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        when(completionService.create(any(ChatCompletionCreateParams.class))).thenThrow(exception);
        return new OpenAiApiPreflight(keyCapturingFactory(client));
    }

    /** Asserts the factory actually receives the caller's key — so {@link #assertKeyNeverLeaked} proves
     *  "the key flowed through the probe and still never reached a Check", not merely that a discarded
     *  literal is absent. */
    private java.util.function.Function<String, OpenAIClient> keyCapturingFactory(OpenAIClient client) {
        return apiKey -> {
            assertThat(apiKey).isEqualTo(SECRET_KEY);
            return client;
        };
    }

    private Headers emptyHeaders() {
        return Headers.builder().build();
    }

    private void assertKeyNeverLeaked(List<Check> checks) {
        for (Check check : checks) {
            assertThat(check.name()).doesNotContain(SECRET_KEY);
            assertThat(check.message()).doesNotContain(SECRET_KEY);
        }
    }
}
