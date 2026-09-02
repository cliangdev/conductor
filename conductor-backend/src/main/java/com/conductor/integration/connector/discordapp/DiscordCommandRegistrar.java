package com.conductor.integration.connector.discordapp;

import com.conductor.exception.DiscordWebhookException;
import com.conductor.integration.ConnectorHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Registers the guild-scoped {@code /ask} slash command a {@code discord-app} connection needs --
 * called once, right after a connection is fully configured (bot token + application/guild id all
 * stored), from {@link com.conductor.integration.connector.discordapp.DiscordAppConnector#onConnectionCreated}
 * overriding the generic {@code Connector#onConnectionCreated} SPI hook (see that hook's javadoc for
 * why creation-time setup goes through a connector-agnostic lifecycle hook rather than connector-specific
 * logic in {@code IntegrationController}).
 *
 * <p>A registration failure fails connection creation outright -- a bad bot token or missing
 * permission must surface now, not silently at the first {@code /ask} a guild member tries.
 */
@Component
@Profile("!local")
public class DiscordCommandRegistrar {

    private static final String API_BASE = "https://discord.com/api/v10";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // @Autowired is load-bearing -- see DiscordActionConnector's identical comment.
    @Autowired
    public DiscordCommandRegistrar(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = ConnectorHttp.restTemplate();
    }

    DiscordCommandRegistrar(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * {@code PUT /applications/{applicationId}/guilds/{guildId}/commands} with the single {@code /ask}
     * command definition -- idempotent/replace-all per Discord's own semantics (safe to call again,
     * e.g. on a future re-save of the same connection).
     */
    public void registerAskCommand(String botToken, String applicationId, String guildId) {
        String url = API_BASE + "/applications/" + applicationId + "/guilds/" + guildId + "/commands";

        ObjectNode question = objectMapper.createObjectNode();
        question.put("type", 3);
        question.put("name", "question");
        question.put("description", "Your question");
        question.put("required", true);
        // Discord allows up to 6000, but a conversation's title column is VARCHAR(200) (DiscordAppConnector
        // truncates before storing) -- 1500 is a generous middle ground: room for a real question, short
        // of provoking a "why was my question cut off" surprise from the 6000 ceiling.
        question.put("max_length", 1500);

        ObjectNode agent = objectMapper.createObjectNode();
        agent.put("type", 3);
        agent.put("name", "agent");
        agent.put("description", "Agent name (defaults to the coordinator)");
        agent.put("required", false);

        ArrayNode options = objectMapper.createArrayNode();
        options.add(question);
        options.add(agent);

        ObjectNode command = objectMapper.createObjectNode();
        command.put("name", "ask");
        command.put("description", "Ask a project agent");
        command.set("options", options);

        ArrayNode payload = objectMapper.createArrayNode();
        payload.add(command);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bot " + botToken);

        HttpEntity<String> request;
        try {
            request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
        } catch (Exception e) {
            throw new DiscordWebhookException("Failed to serialize Discord command payload: " + e.getMessage());
        }

        try {
            restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
        } catch (HttpClientErrorException e) {
            // Message/response-body only -- never the URL (it carries no secret here, application/guild
            // id are not credentials, but staying consistent with the other Discord clients' discipline).
            throw new DiscordWebhookException("Discord rejected /ask command registration ("
                    + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            throw new DiscordWebhookException("Discord command registration failed ("
                    + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            Throwable rootCause = e.getCause() != null ? e.getCause() : e;
            String detail = rootCause.getMessage() != null ? rootCause.getMessage() : rootCause.getClass().getSimpleName();
            throw new DiscordWebhookException("Discord command registration failed (network error): " + detail);
        }
    }
}
