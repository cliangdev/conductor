package com.conductor.integration.connector.local;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookConnector;
import com.conductor.integration.WebhookVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Local-profile stand-in for {@code DiscordAppConnector} -- accepts and logs any interaction without
 * verifying a signature or reaching Discord's API, and does NOT pull in conversation/agent beans it
 * doesn't use (a real {@code /ask} turn against a live agent should go through {@code
 * AgentConversationRunner} directly in local dev, not through this stub). {@code onConnectionCreated}
 * is inherited as the framework's no-op default -- there is no vendor-side command to register locally.
 */
@Component
@Profile("local")
@Primary
public class LocalDiscordAppConnector implements WebhookConnector {

    private static final Logger log = LoggerFactory.getLogger(LocalDiscordAppConnector.class);

    @Override
    public String getId() { return "discord-app"; }

    @Override
    public ConnectorMetadata getMetadata() {
        return new ConnectorMetadata("discord-app", "Discord App", ConnectorCategory.COMMUNICATION,
                "Lets guild members ask a project agent questions via a /ask slash command (local stub).",
                "DA");
    }

    @Override
    public ConnectorSpec getSpec() {
        return ConnectorSpec.apiKey(false, List.of(
                ConnectorConfigField.userInput("application_id", "Application ID",
                        "Local stub -- any value works.", FieldType.STRING, true),
                ConnectorConfigField.userInput("public_key", "Public Key",
                        "Local stub -- any value works.", FieldType.STRING, true),
                ConnectorConfigField.userInput("guild_id", "Server (Guild) ID",
                        "Local stub -- any value works.", FieldType.STRING, true)
        ));
    }

    @Override
    public WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        return WebhookVerification.ok();
    }

    @Override
    public String extractDeliveryId(HttpHeaders headers, byte[] rawBody) {
        return "local-" + System.nanoTime();
    }

    @Override
    public String extractEventType(HttpHeaders headers, byte[] rawBody) {
        return "interaction.local";
    }

    @Override
    public void handleEvent(InboundEvent event, ConnectionContext ctx) {
        log.info("LocalDiscordAppConnector received an interaction -- no-op in local profile");
    }
}
