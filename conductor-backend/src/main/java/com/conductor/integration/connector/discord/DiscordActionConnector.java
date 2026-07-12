package com.conductor.integration.connector.discord;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorHttp;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Outbound Discord webhook connector. The connection's single secret field ({@code webhook_url})
 * rides the same encrypted "apiKey" slot every {@code AuthType.API_KEY} connector uses (see
 * {@code RevenueCatConnector}) — {@link ConnectionContext#accessToken()} returns the raw webhook URL.
 */
@Component
@Profile("!local")
public class DiscordActionConnector implements ActionConnector {

    private static final Logger log = LoggerFactory.getLogger(DiscordActionConnector.class);
    /** Hosts a webhook URL may target — an exact match or a subdomain of one of these. */
    private static final Set<String> ALLOWED_WEBHOOK_HOSTS = Set.of("discord.com", "discordapp.com");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // @Autowired is load-bearing: with TWO constructors and no annotation, Spring falls back to a
    // (nonexistent) no-arg constructor and the context fails AT DEPLOY only — @Profile("!local")
    // beans are never instantiated by tests. Took down the 2026-07-12 preview deploy.
    @Autowired
    public DiscordActionConnector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    DiscordActionConnector(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    public String getId() { return "discord"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("discord", "Discord", ConnectorCategory.COMMUNICATION,
                "Post messages to a Discord channel via an incoming webhook", "DC");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(true, List.of(
            ConnectorConfigField.userInput("webhook_url", "Webhook URL",
                "Discord → Server Settings → Integrations → Webhooks → Copy Webhook URL",
                FieldType.SECRET, true)
        ));
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        if (!"post_message".equals(actionId)) {
            return ActionResult.error("Unknown Discord action: " + actionId);
        }

        String webhookUrl = ctx.accessToken();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return ActionResult.error("Discord webhook not configured");
        }
        // SSRF guard: the configured webhook_url is POSTed to directly and its response is returned
        // into step outputs, so an internal/arbitrary URL here would be an exfil/SSRF vector — require
        // it to actually be a Discord webhook before making any HTTP call.
        if (!isAllowedWebhookUrl(webhookUrl)) {
            return ActionResult.error(
                    "Discord webhook_url must be an https:// URL on discord.com or discordapp.com");
        }

        Object contentObj = input != null ? input.get("content") : null;
        if (contentObj == null || contentObj.toString().isBlank()) {
            return ActionResult.error("Discord post_message requires 'content'");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("content", contentObj.toString());

        Object usernameObj = input.get("username");
        if (usernameObj != null && !usernameObj.toString().isBlank()) {
            body.put("username", usernameObj.toString());
        }

        Object embedsJsonObj = input.get("embeds_json");
        if (embedsJsonObj != null && !embedsJsonObj.toString().isBlank()) {
            try {
                JsonNode embeds = objectMapper.readTree(embedsJsonObj.toString());
                body.set("embeds", embeds);
            } catch (Exception e) {
                // Malformed caller input — permanent, not a connector/network problem, don't retry.
                return ActionResult.error("Discord post_message 'embeds_json' is not valid JSON: " + e.getMessage());
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request;
        try {
            request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        } catch (Exception e) {
            return ActionResult.error("Failed to serialize Discord message body: " + e.getMessage());
        }

        String url = webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true";
        try {
            // 5xx throws past this try — the caller treats a thrown exception as TRANSIENT and
            // retries per ActionConnector's documented contract.
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            return parseSuccess(response.getBody());
        } catch (HttpClientErrorException e) {
            // 4xx = PERMANENT: the request itself was rejected (bad webhook URL, malformed payload).
            log.warn("Discord webhook rejected request: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ActionResult.error("Discord webhook rejected request: " + e.getStatusCode().value()
                    + " " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            // A network I/O failure (connection refused/reset, DNS failure, ...): ResourceAccessException's
            // OWN message embeds the full request URL ("I/O error on POST request for \"<url>\": ..."),
            // and the Discord webhook URL IS the credential (a secret token in its path) — that must
            // never reach a log line or ActionInvocationService's persisted error_message. Use the
            // wrapped cause's message instead (e.g. "Connection refused"), which describes the failure
            // without embedding the URL, and rethrow with NO cause so nothing downstream can unwrap back
            // to the original, unsanitized message. Still a thrown exception, so still classified
            // TRANSIENT per the ActionConnector contract.
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            String detail = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
            throw new RuntimeException("Discord webhook request failed (network error): " + detail);
        }
    }

    /** https scheme, and host exactly one of {@link #ALLOWED_WEBHOOK_HOSTS} or a subdomain of one. */
    private boolean isAllowedWebhookUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return ALLOWED_WEBHOOK_HOSTS.stream()
                    .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ActionResult parseSuccess(String responseBody) {
        Map<String, Object> output = new HashMap<>();
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(responseBody);
                if (node.hasNonNull("id")) output.put("message_id", node.get("id").asText());
                if (node.hasNonNull("channel_id")) output.put("channel_id", node.get("channel_id").asText());
            } catch (Exception e) {
                log.warn("Discord post_message: could not parse response body: {}", e.getMessage());
            }
        }
        return ActionResult.ok(output);
    }
}
