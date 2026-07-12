package com.conductor.integration.connector.discord;

import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordActionConnectorTest {

    private static final ConnectionContext CTX = new ConnectionContext(
            "proj", "discord", "conn", "https://discord.com/api/webhooks/1/token", null, null, Map.of(), null);

    @Test
    void postMessage_success_returnsMessageIdAndChannelId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyUrl(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"999\",\"channel_id\":\"111\"}"));

        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);
        ActionResult result = connector.invoke("post_message", Map.of("content", "hello"), CTX);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("message_id", "999");
        assertThat(result.output()).containsEntry("channel_id", "111");
    }

    @Test
    void postMessage_missingContent_returnsPermanentErrorWithoutCallingHttp() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);

        ActionResult result = connector.invoke("post_message", Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("content");
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    void postMessage_missingWebhookUrl_returnsPermanentError() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);
        ConnectionContext noUrl = new ConnectionContext("proj", "discord", "conn", null, null, null, Map.of(), null);

        ActionResult result = connector.invoke("post_message", Map.of("content", "hi"), noUrl);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not configured");
    }

    @Test
    void postMessage_4xx_isPermanent_returnsErrorWithoutThrowing() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyUrl(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        new HttpHeaders(), "invalid webhook".getBytes(), null));

        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);
        ActionResult result = connector.invoke("post_message", Map.of("content", "hi"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("400");
    }

    @Test
    void postMessage_5xx_isTransient_propagatesAsThrown() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyUrl(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error",
                        new HttpHeaders(), "boom".getBytes(), null));

        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);

        // Per the ActionConnector contract, a 5xx must propagate as a thrown exception (TRANSIENT) —
        // not be swallowed into an ActionResult.error(...) — so ActionInvocationService retries it.
        assertThatThrownBy(() -> connector.invoke("post_message", Map.of("content", "hi"), CTX))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void postMessage_malformedEmbedsJson_returnsPermanentErrorWithoutCallingHttp() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper(), restTemplate);

        ActionResult result = connector.invoke("post_message",
                Map.of("content", "hi", "embeds_json", "not-json"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("embeds_json");
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    /** Direct-construction production-shape smoke test — guards against the {@code @Profile("!local")} deploy-only gap. */
    @Test
    void directConstruction_withProductionShapeArgs_doesNotThrow() {
        DiscordActionConnector connector = new DiscordActionConnector(new ObjectMapper());
        assertThat(connector.getId()).isEqualTo("discord");
        assertThat(connector.getActions()).extracting("id").containsExactly("post_message");
    }

    private static String anyUrl() {
        return org.mockito.ArgumentMatchers.contains("wait=true");
    }
}
