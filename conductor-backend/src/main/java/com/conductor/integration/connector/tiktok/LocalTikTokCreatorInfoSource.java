package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code local} profile's creator source: nothing here touches the network. It answers from what the
 * connection already cached at connect time, so the consent walk is reachable on a developer machine.
 * A test can set {@code commentDisabled}/{@code duetDisabled}/{@code stitchDisabled} in the connection's
 * config to see a creator who has switched an interaction off.
 */
@Component
@Profile("local")
public class LocalTikTokCreatorInfoSource implements TikTokCreatorInfoSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public TikTokClient.CreatorInfo query(Connection connection) {
        JsonNode config;
        try {
            config = connection.getConfigJson() == null ? MAPPER.createObjectNode()
                    : MAPPER.readTree(connection.getConfigJson());
        } catch (Exception e) {
            config = MAPPER.createObjectNode();
        }
        List<String> levels = new ArrayList<>();
        config.path(TikTokConnector.CONFIG_PRIVACY_LEVEL_OPTIONS).forEach(node -> levels.add(node.asText()));
        JsonNode max = config.path(TikTokConnector.CONFIG_MAX_VIDEO_DURATION_SEC);
        return new TikTokClient.CreatorInfo(
                text(config, TikTokConnector.CONFIG_CREATOR_NICKNAME),
                text(config, TikTokConnector.CONFIG_CREATOR_USERNAME), levels,
                max.isNumber() ? max.asInt() : null,
                config.path(TikTokConnector.CONFIG_COMMENT_DISABLED).asBoolean(false),
                config.path(TikTokConnector.CONFIG_DUET_DISABLED).asBoolean(false),
                config.path(TikTokConnector.CONFIG_STITCH_DISABLED).asBoolean(false),
                null, true, null);
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() ? value.asText() : null;
    }
}
