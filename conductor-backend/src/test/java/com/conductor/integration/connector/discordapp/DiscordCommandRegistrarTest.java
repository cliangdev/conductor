package com.conductor.integration.connector.discordapp;

import com.conductor.exception.DiscordWebhookException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordCommandRegistrarTest {

    @Test
    void registerAskCommand_sendsQuestionRequiredAndAgentOptionalOptions() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/applications/app-1/guilds/guild-1/commands"),
                eq(HttpMethod.PUT), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        ObjectMapper objectMapper = new ObjectMapper();
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(objectMapper, restTemplate);

        registrar.registerAskCommand("bot-token", "app-1", "guild-1");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<HttpEntity<String>> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(contains("/applications/app-1/guilds/guild-1/commands"),
                eq(HttpMethod.PUT), captor.capture(), eq(String.class));

        HttpEntity<String> entity = captor.getValue();
        assertThat(entity.getHeaders().getFirst("Authorization")).isEqualTo("Bot bot-token");

        JsonNode payload = objectMapper.readTree(entity.getBody());
        assertThat(payload).hasSize(1);
        JsonNode command = payload.get(0);
        assertThat(command.get("name").asText()).isEqualTo("ask");

        JsonNode options = command.get("options");
        assertThat(options).hasSize(2);
        assertThat(options.get(0).get("name").asText()).isEqualTo("question");
        assertThat(options.get(0).get("required").asBoolean()).isTrue();
        // Discord truncates nothing server-side -- Conductor's own conversations.title column (VARCHAR(200))
        // is what actually needs protecting, so the option itself caps well above that with room to spare.
        assertThat(options.get(0).get("max_length").asInt()).isEqualTo(1500);
        assertThat(options.get(1).get("name").asText()).isEqualTo("agent");
        assertThat(options.get(1).get("required").asBoolean()).isFalse();
    }

    @Test
    void registerAskCommand_5xx_throwsDiscordWebhookException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/commands"), eq(HttpMethod.PUT), any(), eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", new HttpHeaders(), "boom".getBytes(), null));
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> registrar.registerAskCommand("bot-token", "app-1", "guild-1"))
                .isInstanceOf(DiscordWebhookException.class)
                .hasMessageContaining("500");
    }

    @Test
    void registerAskCommand_4xx_throwsDiscordWebhookExceptionWithResponseBody() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(contains("/commands"), eq(HttpMethod.PUT), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                        new HttpHeaders(), "invalid bot token".getBytes(), null));
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> registrar.registerAskCommand("bad-token", "app-1", "guild-1"))
                .isInstanceOf(DiscordWebhookException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid bot token");
    }

    @Test
    void registerAskCommand_networkError_sanitizesUrlOutOfThrownException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ResourceAccessException resourceAccessException = new ResourceAccessException(
                "I/O error on PUT request for \"https://discord.com/api/v10/applications/app-1/guilds/guild-1/commands\": Connection refused",
                new ConnectException("Connection refused"));
        when(restTemplate.exchange(contains("/commands"), eq(HttpMethod.PUT), any(), eq(String.class)))
                .thenThrow(resourceAccessException);
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(new ObjectMapper(), restTemplate);

        assertThatThrownBy(() -> registrar.registerAskCommand("bot-token", "app-1", "guild-1"))
                .isInstanceOf(DiscordWebhookException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("discord.com/api/v10");
                    assertThat(e.getMessage()).contains("Connection refused");
                    assertThat(e.getCause()).isNull();
                });
    }

    /** Direct-construction production-shape smoke test -- guards against the {@code @Profile("!local")} deploy-only gap. */
    @Test
    void directConstruction_withProductionShapeArgs_doesNotThrow() {
        DiscordCommandRegistrar registrar = new DiscordCommandRegistrar(new ObjectMapper());
        assertThat(registrar).isNotNull();
    }
}
