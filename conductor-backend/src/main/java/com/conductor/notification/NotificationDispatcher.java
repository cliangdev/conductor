package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.workflow.WorkflowTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationGroupConfigRepository groupConfigRepository;
    private final DiscordProvider discordProvider;

    @Lazy
    @Autowired
    private WorkflowTriggerService workflowTriggerService;

    public NotificationDispatcher(NotificationGroupConfigRepository groupConfigRepository,
                                  DiscordProvider discordProvider) {
        this.groupConfigRepository = groupConfigRepository;
        this.discordProvider = discordProvider;
    }

    public void dispatch(NotificationEvent event) {
        sendNotification(event);

        try {
            workflowTriggerService.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Workflow trigger evaluation failed for event {}: {}", event.getEventType(), e.getMessage());
        }
    }

    private void sendNotification(NotificationEvent event) {
        Optional<ChannelGroup> groupOpt = ChannelGroup.forEventType(event.getEventType());
        if (groupOpt.isEmpty()) {
            log.debug("No channel group defined for event type: {}", event.getEventType());
            return;
        }

        ChannelGroup group = groupOpt.get();

        Optional<NotificationGroupConfig> configOpt =
                groupConfigRepository.findByProjectIdAndChannelGroup(event.getProjectId(), group);
        if (configOpt.isEmpty()) {
            return;
        }

        NotificationGroupConfig config = configOpt.get();

        if (!config.isEnabled()) {
            return;
        }

        if (!config.getEnabledEventTypes().contains(event.getEventType().name())) {
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
