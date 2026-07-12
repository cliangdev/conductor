package com.conductor.integration.connector.discord;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionDescriptor;
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
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound Discord webhook connector. The connection's single secret field ({@code webhook_url})
 * rides the same encrypted "apiKey" slot every {@code AuthType.API_KEY} connector uses (see
 * {@code RevenueCatConnector}) — {@link ConnectionContext#accessToken()} returns the raw webhook URL.
 */
@Component
@Profile("!local")
public class DiscordActionConnector implements ActionConnector {

    private static final Logger log = LoggerFactory.getLogger(DiscordActionConnector.class);
    static final String POST_MESSAGE = "post_message";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

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
    public List<ActionDescriptor> getActions() {
        return List.of(new ActionDescriptor(POST_MESSAGE, "Post a message to the channel",
                List.of("content", "username", "embeds_json")));
    }

    @Override
    public ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx) {
        if (!POST_MESSAGE.equals(actionId)) {
            return ActionResult.error("Unknown Discord action: " + actionId);
        }

        String webhookUrl = ctx.accessToken();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return ActionResult.error("Discord webhook not configured");
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
            // 5xx (and network I/O errors) throw past this try — the caller treats a thrown
            // exception as TRANSIENT and retries per ActionConnector's documented contract.
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            return parseSuccess(response.getBody());
        } catch (HttpClientErrorException e) {
            // 4xx = PERMANENT: the request itself was rejected (bad webhook URL, malformed payload).
            log.warn("Discord webhook rejected request: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ActionResult.error("Discord webhook rejected request: " + e.getStatusCode().value()
                    + " " + e.getResponseBodyAsString());
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
