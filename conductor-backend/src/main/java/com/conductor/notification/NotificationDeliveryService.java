package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final NotificationRollupService rollupService;

    public NotificationDeliveryService(NotificationGroupConfigRepository groupConfigRepository,
                                       DiscordProvider discordProvider,
                                       NotificationRollupService rollupService) {
        this.groupConfigRepository = groupConfigRepository;
        this.discordProvider = discordProvider;
        this.rollupService = rollupService;
    }

    /**
     * Sends the event to the project's channel for its {@link ChannelGroup}, if one is configured and
     * enabled and has opted into this event type. A no-op for the event types that belong to no
     * channel group ({@code ASSET_ADDED}, {@code GITHUB_PULL_REQUEST}) -- those flow through the
     * dispatcher for their side effects only, never as a chat message.
     */
    public void deliver(NotificationMessage event) {
        List<ChannelGroup> candidates = ChannelGroup.forEvent(event.getEventType(), event.getMetadata());
        if (candidates.isEmpty()) {
            log.debug("No channel group defined for event type: {}", event.getEventType());
            return;
        }

        // Most specific first, falling through to the next when a group has no usable config. A Post's
        // status change prefers the Publishing channel and lands in the Issues one when a project has not
        // set Publishing up — so adding the group cannot take away notifications a project already gets.
        NotificationGroupConfig config = null;
        for (ChannelGroup candidate : candidates) {
            NotificationGroupConfig found = groupConfigRepository
                    .findByProjectIdAndChannelGroup(event.getProjectId(), candidate)
                    .filter(NotificationGroupConfig::isEnabled)
                    .filter(c -> c.getEnabledEventTypes().contains(event.getEventType().name()))
                    .orElse(null);
            if (found != null) {
                config = found;
                break;
            }
        }
        if (config == null) {
            return;
        }
        // Status changes and review events fold into the channel's rolling message; everything else is
        // sent as its own message, as before.
        if (NotificationRollupService.appliesTo(config, event)) {
            rollupAfterCommit(config, event);
        } else {
            send(config, event);
        }
    }

    /**
     * Runs the roll-up once the caller's transaction has committed, or straight away when there is none.
     *
     * <p>The roll-up locks the channel's config row and makes Discord calls, so it must not run inside
     * the caller's transaction: it would hold that lock until the caller commits, could deadlock with
     * lifecycle cascades that update parent Work Items after this ran, and a database error swallowed in
     * it would still mark the caller rollback-only and fail the user's status change or review. After
     * commit it opens its own short transaction (two connections are held, but briefly, and the first has
     * no locks), and whatever it does can no longer affect the caller's data -- a failure can only cost
     * the notification. If the caller rolls back, nothing is sent: no notification for a change that did
     * not happen.
     *
     * <p>Events are buffered per transaction and per channel, so one request that emits several (approving
     * a Post is a review, then Approved, then Scheduled) costs one roll-up transaction and one Discord
     * call per channel rather than one of each per event.
     *
     * <p>Nothing thrown here escapes: by then there is nobody to tell, and {@code afterCommit} exceptions
     * would otherwise surface from the caller's commit.
     */
    private void rollupAfterCommit(NotificationGroupConfig config, NotificationMessage event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            pendingRollups().add(config, event);
        } else {
            rollupNow(config.getId(), List.of(new Buffered(config, event)));
        }
    }

    /**
     * The current transaction's buffer, registering it on first use. It lives in the synchronization
     * itself, so it is per transaction by construction (a suspended outer transaction keeps its own) and
     * the framework drops it with the synchronizations -- nothing is left on a pooled thread.
     */
    private PendingRollups pendingRollups() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof PendingRollups pending) {
                return pending;
            }
        }
        PendingRollups pending = new PendingRollups();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private void rollupNow(String configId, List<Buffered> buffered) {
        try {
            // false means the roll-up declined (or could not post anything): send each event the ordinary
            // way, in order. Safe because nothing reached Discord.
            List<NotificationMessage> events = buffered.stream().map(Buffered::event).toList();
            if (!rollupService.rollup(configId, events)) {
                buffered.forEach(b -> send(b.config(), b.event()));
            }
        } catch (Exception e) {
            NotificationMessage first = buffered.get(0).event();
            log.warn("Failed to roll up {} notification(s) for project {}: {}",
                    first.getEventType(), first.getProjectId(), e.getMessage());
        }
    }

    private record Buffered(NotificationGroupConfig config, NotificationMessage event) { }

    /** Rolled-up events of one transaction by channel, in the order they were emitted. */
    private final class PendingRollups implements TransactionSynchronization {

        private final Map<String, List<Buffered>> byConfig = new LinkedHashMap<>();

        void add(NotificationGroupConfig config, NotificationMessage event) {
            byConfig.computeIfAbsent(config.getId(), id -> new ArrayList<>()).add(new Buffered(config, event));
        }

        @Override
        public void afterCommit() {
            byConfig.forEach(NotificationDeliveryService.this::rollupNow);
        }

        @Override
        public void afterCompletion(int status) {
            // Commit or rollback: either way the buffer is done with.
            byConfig.clear();
        }
    }

    /**
     * Delivers to one named group, skipping routing entirely.
     *
     * <p>For "test this channel", where re-deriving the destination would defeat the point: a specialised
     * group is chosen from an event's metadata, and a synthetic test event has none, so a routed test of
     * the Publishing channel would quietly go to the Issues one — reporting success about a channel it
     * never touched. Testing a channel has to mean testing <em>that</em> channel.
     *
     * @return true when a message actually reached the provider, so a caller can tell a human what really
     *         happened rather than that the attempt was made
     */
    public boolean deliverTo(ChannelGroup group, NotificationMessage event) {
        NotificationGroupConfig config = groupConfigRepository
                .findByProjectIdAndChannelGroup(event.getProjectId(), group)
                .filter(NotificationGroupConfig::isEnabled)
                .orElse(null);
        return config != null && send(config, event);
    }

    /** @return true when the provider accepted the message. */
    private boolean send(NotificationGroupConfig config, NotificationMessage event) {
        NotificationProvider provider = resolveProvider(config.getProvider());
        if (provider == null) {
            log.warn("No provider implementation for: {}", config.getProvider());
            return false;
        }

        try {
            String formatted = provider.format(event);
            provider.send(config.getWebhookUrl(), formatted);
            return true;
        } catch (Exception e) {
            log.warn("Failed to dispatch {} notification for project {}: {}",
                    event.getEventType(), event.getProjectId(), e.getMessage());
            return false;
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
