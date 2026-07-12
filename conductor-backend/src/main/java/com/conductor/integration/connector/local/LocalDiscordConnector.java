package com.conductor.integration.connector.local;

import com.conductor.integration.ActionConnector;
import com.conductor.integration.ActionDescriptor;
import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorConfigField;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.FieldType;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("local")
@Primary
public class LocalDiscordConnector implements ActionConnector {

    static final String POST_MESSAGE = "post_message";

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
        return ActionResult.ok(Map.of("message_id", "local-1", "channel_id", "local"));
    }
}
