package com.conductor.integration.connector.discordapp;

import com.conductor.exception.DiscordWebhookException;
import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Outbound Discord REST API v10 calls for the {@code /ask} interaction flow: editing the deferred
 * "@original" response once the agent has an answer, and turning that message into a thread for a
 * fresh (non-thread-invocation) conversation. Kept separate from {@link DiscordAppConnector} (which
 * owns inbound verify/routing) so the outbound HTTP shape is independently testable, same split as
 * {@code DiscordActionConnector} owning its own outbound call.
 *
 * <p>Both calls: a single bounded retry on {@code 429} honoring {@code Retry-After}; {@code 401}/{@code
 * 404} (and any other non-2xx) surface as {@link DiscordWebhookException} (mapped to {@code 502} by
 * {@code GlobalExceptionHandler} -- Conductor acting as a gateway to a misbehaving/rejecting upstream).
 * The interaction token and bot token are both request-URL/header secrets with a short lifetime (an
 * interaction token expires in 15 minutes) -- never logged, and never allowed to leak via a raw {@link
 * ResourceAccessException} message, which (like {@code DiscordActionConnector}'s webhook URL case)
 * embeds the full request URL verbatim.
 */
@Component
@Profile("!local")
public class DiscordInteractionClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordInteractionClient.class);
    private static final String API_BASE = "https://discord.com/api/v10";
    private static final int MAX_ATTEMPTS = 2;
    private static final long DEFAULT_RETRY_AFTER_MS = 1000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** One reference to the edited "@original" interaction response message. */
    public record MessageRef(String messageId, String channelId) {}

    /** One reference to a newly created thread. */
    public record ThreadRef(String id) {}

    // @Autowired is load-bearing -- see DiscordActionConnector's identical comment: with two
    // constructors and no annotation, Spring falls back to a nonexistent no-arg constructor and the
    // context fails only at deploy (this @Profile("!local") bean is never instantiated by tests).
    @Autowired
    public DiscordInteractionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    DiscordInteractionClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /** {@code PATCH /webhooks/{applicationId}/{interactionToken}/messages/@original}. */
    public MessageRef editOriginal(String applicationId, String interactionToken, String content) {
        String url = API_BASE + "/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", content);
        JsonNode response = exchangeWithRetry(url, HttpMethod.PATCH, body, null);
        return new MessageRef(textOrNull(response, "id"), textOrNull(response, "channel_id"));
    }

    /** {@code POST /channels/{channelId}/messages/{messageId}/threads}, bot-token authenticated. */
    public ThreadRef createThread(String botToken, String channelId, String messageId, String name) {
        String url = API_BASE + "/channels/" + channelId + "/messages/" + messageId + "/threads";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        JsonNode response = exchangeWithRetry(url, HttpMethod.POST, body, botToken);
        return new ThreadRef(textOrNull(response, "id"));
    }

    private JsonNode exchangeWithRetry(String url, HttpMethod method, ObjectNode body, String botToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (botToken != null) {
            headers.set("Authorization", "Bot " + botToken);
        }
        HttpEntity<String> request;
        try {
            request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        } catch (Exception e) {
            throw new DiscordWebhookException("Failed to serialize Discord request body: " + e.getMessage());
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, method, request, String.class);
                return parse(response.getBody());
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS && attempt < MAX_ATTEMPTS) {
                    long retryAfterMs = retryAfterMillis(e);
                    log.warn("Discord API rate-limited (429) — retrying once after {}ms", retryAfterMs);
                    sleep(retryAfterMs);
                    continue;
                }
                // Status text + response body only -- HttpClientErrorException's message never embeds
                // the request URL (unlike ResourceAccessException below), so this is safe to surface.
                throw new DiscordWebhookException("Discord API rejected the request ("
                        + e.getStatusCode().value() + "): " + e.getStatusText());
            } catch (ResourceAccessException e) {
                // Same sanitization as DiscordActionConnector: e's own message embeds the full request
                // URL (which carries the interaction/bot token) -- use the wrapped cause's message
                // instead, and rethrow with no cause so nothing downstream can unwrap back to it.
                Throwable rootCause = e.getCause() != null ? e.getCause() : e;
                String detail = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
                throw new DiscordWebhookException("Discord API request failed (network error): " + detail);
            }
        }
        throw new DiscordWebhookException("Discord API rate-limited (429) after retry");
    }

    private long retryAfterMillis(HttpClientErrorException e) {
        String header = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (header == null) {
            return DEFAULT_RETRY_AFTER_MS;
        }
        try {
            return Math.max(0, (long) (Double.parseDouble(header) * 1000));
        } catch (NumberFormatException ex) {
            return DEFAULT_RETRY_AFTER_MS;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DiscordWebhookException("Interrupted while backing off a Discord 429 retry");
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
