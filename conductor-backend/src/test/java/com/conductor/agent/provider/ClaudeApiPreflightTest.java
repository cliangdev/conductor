package com.conductor.agent.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring, no real network call) for {@link ClaudeApiPreflight}'s exception-to-check
 * mapping. Uses the package-private client-factory constructor to inject a stub {@link AnthropicClient}
 * whose {@code messages().create(...)} either succeeds or throws a specific SDK exception type, so each
 * branch of the mapping is exercised deterministically.
 */
class ClaudeApiPreflightTest {

    private static final String SECRET_KEY = "sk-ant-super-secret-do-not-leak";

    @Test
    void check_success_returnsPass() {
        ClaudeApiPreflight preflight = preflightThatReturns(mock(Message.class));

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.PASS);
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_unauthorized_returnsFail() {
        ClaudeApiPreflight preflight = preflightThatThrows(UnauthorizedException.builder().headers(emptyHeaders()).body(JsonValue.from("{}")).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("401");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_permissionDenied_returnsFail() {
        ClaudeApiPreflight preflight = preflightThatThrows(PermissionDeniedException.builder().headers(emptyHeaders()).body(JsonValue.from("{}")).build());

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
                .body(JsonValue.from("Your credit balance is too low to access the Anthropic API"))
                .build();
        ClaudeApiPreflight preflight = preflightThatThrows(billing);

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("billing");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_nonBillingBadRequest_returnsFailWithGenericMessage() {
        ClaudeApiPreflight preflight = preflightThatThrows(BadRequestException.builder().headers(emptyHeaders()).body(JsonValue.from("{}")).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("400");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_rateLimited_returnsWarnNotFail() {
        ClaudeApiPreflight preflight = preflightThatThrows(RateLimitException.builder().headers(emptyHeaders()).body(JsonValue.from("{}")).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        // 429 proves the key IS valid (Anthropic only rate-limits authenticated requests) — must never
        // be a fail, or the overall verification would wrongly flip to "error" for a working key.
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.WARN);
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_internalServerError_returnsFailCouldNotReach() {
        ClaudeApiPreflight preflight = preflightThatThrows(InternalServerException.builder().statusCode(500).headers(emptyHeaders()).body(JsonValue.from("{}")).build());

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("could not reach anthropic");
        assertKeyNeverLeaked(checks);
    }

    @Test
    void check_timeoutOrNetworkError_returnsFailCouldNotReach() {
        ClaudeApiPreflight preflight = preflightThatThrows(new AnthropicIoException("timed out"));

        List<Check> checks = preflight.check(SECRET_KEY);

        assertThat(checks).hasSize(1);
        assertThat(checks.get(0).status()).isEqualTo(CheckStatus.FAIL);
        assertThat(checks.get(0).message()).containsIgnoringCase("could not reach anthropic");
        assertKeyNeverLeaked(checks);
    }

    private ClaudeApiPreflight preflightThatReturns(Message message) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messageService = mock(MessageService.class);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(message);
        return new ClaudeApiPreflight(keyCapturingFactory(client));
    }

    private ClaudeApiPreflight preflightThatThrows(RuntimeException exception) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messageService = mock(MessageService.class);
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenThrow(exception);
        return new ClaudeApiPreflight(keyCapturingFactory(client));
    }

    /** Asserts the factory actually receives the caller's key — so {@link #assertKeyNeverLeaked} proves
     *  "the key flowed through the probe and still never reached a Check", not merely that a discarded
     *  literal is absent. */
    private java.util.function.Function<String, AnthropicClient> keyCapturingFactory(AnthropicClient client) {
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
