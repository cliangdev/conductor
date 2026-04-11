package com.conductor.notification;

import com.conductor.entity.NotificationChannelConfig;
import com.conductor.repository.NotificationChannelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationChannelConfigRepository channelConfigRepository;
    private final DiscordProvider discordProvider;

    public NotificationDispatcher(NotificationChannelConfigRepository channelConfigRepository, DiscordProvider discordProvider) {
        this.channelConfigRepository = channelConfigRepository;
        this.discordProvider = discordProvider;
    }

    public void dispatch(NotificationEvent event) {
        Optional<NotificationChannelConfig> configOpt = channelConfigRepository
                .findByProjectIdAndEventType(event.getProjectId(), event.getEventType());

        if (configOpt.isEmpty()) {
            return;
        }

        NotificationChannelConfig config = configOpt.get();
        if (!config.isEnabled()) {
            return;
        }

        NotificationProvider provider = resolveProvider(config.getProvider());
        if (provider == null) {
            log.warn("No provider implementation for: {}", config.getProvider());
            return;
        }

        try {
            String formatted = provider.format(event);
            provider.send(config.getWebhookUrl(), formatted);
        } catch (Exception e) {
            log.warn("Failed to dispatch {} notification for project {}: {}",
                    event.getEventType(), event.getProjectId(), e.getMessage());
        }
    }

    private NotificationProvider resolveProvider(ProviderType providerType) {
        if (providerType == ProviderType.DISCORD) {
            return discordProvider;
        }
        return null;
    }
}
