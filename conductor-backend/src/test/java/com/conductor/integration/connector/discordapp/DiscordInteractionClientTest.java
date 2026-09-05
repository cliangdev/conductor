package com.conductor.integration.connector.discordapp;

import com.conductor.exception.DiscordWebhookException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordInteractionClientTest {

    @Test
    void editOriginal_success_returnsMessageAndChannelId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"msg-1\",\"channel_id\":\"chan-1\"}"));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        DiscordInteractionClient.MessageRef ref = client.editOriginal("app-1", "tok-1", "hello");

        assertThat(ref.messageId()).isEqualTo("msg-1");
        assertThat(ref.channelId()).isEqualTo("chan-1");
    }

    @Test
    void createThread_success_returnsThreadId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/threads"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"thread-1\"}"));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        DiscordInteractionClient.ThreadRef ref = client.createThread("bot-token", "chan-1", "msg-1", "my thread");

        assertThat(ref.id()).isEqualTo("thread-1");
    }

    @Test
    void editOriginal_rateLimited_retriesOnceThenSucceeds() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "0");
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        retryHeaders, new byte[0], null))
                .thenReturn(ResponseEntity.ok("{\"id\":\"msg-1\",\"channel_id\":\"chan-1\"}"));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        DiscordInteractionClient.MessageRef ref = client.editOriginal("app-1", "tok-1", "hello");

        assertThat(ref.messageId()).isEqualTo("msg-1");
        verify(restTemplate, times(2)).exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class));
    }

    @Test
    void editOriginal_rateLimitedTwice_throwsAfterOneRetry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "0");
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        retryHeaders, new byte[0], null));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.editOriginal("app-1", "tok-1", "hello"))
                .isInstanceOf(DiscordWebhookException.class);
        verify(restTemplate, times(2)).exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class));
    }

    @Test
    void editOriginal_4xx_wrapsAsDiscordWebhookException() {
        // Not 404 -- editOriginal retries once on a bare 404 (see the tests below), which would make
        // this generic-4xx-wraps-cleanly test also pay a ~1.5s retry sleep for no reason.
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        new HttpHeaders(), new byte[0], null));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.editOriginal("app-1", "tok-1", "hello"))
                .isInstanceOf(DiscordWebhookException.class)
                .hasMessageContaining("400");
    }

    @Test
    void editOriginal_5xx_wrapsAsDiscordWebhookException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(), new byte[0], null));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.editOriginal("app-1", "tok-1", "hello"))
                .isInstanceOf(DiscordWebhookException.class)
                .hasMessageContaining("502");
    }

    /**
     * The race this retry exists for: {@code DiscordAppConnector} now enqueues the whole {@code /ask}
     * flow and returns immediately, so this call can reach Discord before Discord has finished
     * processing the ack response that same webhook request is still sending -- Discord briefly has no
     * "original interaction response" to edit, hence a transient 404. A short delay (real, not
     * mocked -- this test genuinely sleeps ~1.5s) then one retry absorbs that window.
     */
    @Test
    void editOriginal_404_retriesOnceThenSucceeds() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        new HttpHeaders(), new byte[0], null))
                .thenReturn(ResponseEntity.ok("{\"id\":\"msg-1\",\"channel_id\":\"chan-1\"}"));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        DiscordInteractionClient.MessageRef ref = client.editOriginal("app-1", "tok-1", "hello");

        assertThat(ref.messageId()).isEqualTo("msg-1");
        verify(restTemplate, times(2)).exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class));
    }

    @Test
    void editOriginal_404Twice_throwsAfterOneRetry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        new HttpHeaders(), new byte[0], null));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.editOriginal("app-1", "tok-1", "hello"))
                .isInstanceOf(DiscordWebhookException.class)
                .hasMessageContaining("404");
        verify(restTemplate, times(2)).exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class));
    }

    /** {@code createThread} does NOT opt into the 404 retry -- there is no equivalent race for it (it
     *  runs well after {@code editOriginal} already succeeded), so a 404 there is a genuine failure. */
    @Test
    void createThread_404_doesNotRetry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/threads"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                        new HttpHeaders(), new byte[0], null));
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.createThread("bot-token", "chan-1", "msg-1", "name"))
                .isInstanceOf(DiscordWebhookException.class);
        verify(restTemplate, times(1)).exchange(contains("/threads"), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void editOriginal_networkError_sanitizesTokenOutOfThrownException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResourceAccessException resourceAccessException = new ResourceAccessException(
                "I/O error on PATCH request for \"https://discord.com/api/v10/webhooks/app-1/tok-1/messages/@original\": Connection refused",
                new ConnectException("Connection refused"));
        when(restTemplate.exchange(contains("/messages/@original"), eq(HttpMethod.PATCH), any(), eq(String.class)))
                .thenThrow(resourceAccessException);
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> client.editOriginal("app-1", "tok-1", "hello"))
                .isInstanceOf(DiscordWebhookException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("tok-1");
                    assertThat(e.getMessage()).doesNotContain("discord.com/api/v10");
                    assertThat(e.getMessage()).contains("Connection refused");
                    assertThat(e.getCause()).isNull();
                });
    }

    /** Direct-construction production-shape smoke test -- guards against the {@code @Profile("!local")} deploy-only gap. */
    @Test
    void directConstruction_withProductionShapeArgs_doesNotThrow() {
        DiscordInteractionClient client = new DiscordInteractionClient(new ObjectMapper());
        assertThat(client).isNotNull();
    }
}
