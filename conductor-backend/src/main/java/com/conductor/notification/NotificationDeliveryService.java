package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Delivers a {@link NotificationMessage} to a project's configured chat channel. This is *only* the
 * outbound-message concern, called both by {@code NotificationSignalSink} (for real signals flowing
 * through the {@code SignalBus}) and directly by {@code NotificationChannelService}/{@code
 * NotificationGroupService} (for their admin "send a test notification" buttons, which deliberately do
 * NOT touch the bus -- see those classes' javadoc).
 *
 * <h2>The lookup is deliberately unguarded</h2>
 * {@link #deliver} wraps only {@code provider.format}/{@code provider.send} in a try/catch. Everything
 * before that -- the {@link ChannelGroup} resolution and the {@code groupConfigRepository} read -- is
 * bare, so a DB failure there propagates to the caller rather than being swallowed.
 *
 * <p>That is not an oversight, and it must not be "tidied up" into a catch-all: on the {@code
 * SignalBus} path, {@code NotificationSignalSink} runs first (order {@code NOTIFICATION}) with {@code
 * FailureMode.PROPAGATE}, so a failing config lookup escapes {@code InProcessSignalBus.publish} entirely
 * and prevents the downstream subscribers (workflow triggers, lifecycle cascade, knowledge ingestion)
 * from running at all for that signal. On the GitHub webhook path that escape is what marks the {@code
 * webhook_event} FAILED and gets it retried, so the behaviour is load-bearing rather than incidental.
 * It is pinned by {@code InProcessSignalBusTest#propagateFailureRethrowsAndStopsLaterSubscribers} (the
 * generic PROPAGATE mechanism) together with {@code
 * NotificationSignalSinkTest#aFailingDeliverEscapesOnSignalUnguarded} (that this class's failure is what
 * escapes).
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationGroupConfigRepository groupConfigRepository;
    private final DiscordProvider discordProvider;

    public NotificationDeliveryService(NotificationGroupConfigRepository groupConfigRepository,
                                       DiscordProvider discordProvider) {
        this.groupConfigRepository = groupConfigRepository;
        this.discordProvider = discordProvider;
    }

    /**
     * Sends the event to the project's channel for its {@link ChannelGroup}, if one is configured and
     * enabled and has opted into this event type. A no-op for the event types that belong to no
     * channel group ({@code ASSET_ADDED}, {@code WORKFLOW_AUTO_PAUSED}, {@code GITHUB_PULL_REQUEST}) --
     * those flow through the dispatcher for their side effects only, never as a chat message.
     */
    public void deliver(NotificationMessage event) {
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

    /** Only Discord has an implementation today; SLACK/TEAMS are declared in {@link ProviderType} but unbuilt. */
    private NotificationProvider resolveProvider(ProviderType providerType) {
        if (providerType == ProviderType.DISCORD) {
            return discordProvider;
        }
        return null;
    }
}
