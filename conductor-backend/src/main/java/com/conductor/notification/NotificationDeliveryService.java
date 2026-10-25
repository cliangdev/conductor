package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        // sent as its own message, as before. Either way nothing leaves until the caller's transaction has
        // committed, and in the order the events were emitted (see deferOrRun).
        Delivery delivery = new Delivery(config, event, NotificationRollupService.appliesTo(config, event));
        deferOrRun(delivery);
    }

    /**
     * Sends once the caller's transaction has committed, or straight away when there is none.
     *
     * <p>Every delivery waits, not only the rolled-up ones. A change that rolls back must send nothing at
     * all -- no "moved to Scheduled", no "needs publishing by hand" for something that did not happen --
     * and the order of messages must be the order of events: deferring only some would send the rest
     * mid-transaction, ahead of the ones that wait. The roll-up in particular locks the channel's config
     * row and makes Discord calls, so it must not run inside the caller's transaction: it would hold that
     * lock until the caller commits, could deadlock with lifecycle cascades that update parent Work Items
     * after this ran, and a database error swallowed in it would still mark the caller rollback-only. After
     * commit it opens its own short transaction, and whatever it does can no longer affect the caller's
     * data -- a failure can only cost the notification.
     *
     * <p>Delivered immediately when there is no transaction to wait for, or registering with it is not
     * possible.
     */
    private void deferOrRun(Delivery delivery) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                pendingDeliveries().add(delivery);
                return;
            } catch (IllegalStateException e) {
                log.debug("Could not defer a notification to commit; delivering now: {}", e.getMessage());
            }
        }
        runNow(List.of(delivery));
    }

    /**
     * The current transaction's buffer, registering it on first use. It lives in the synchronization
     * itself, so it is per transaction by construction (a suspended outer transaction keeps its own) and
     * the framework drops it with the synchronizations -- nothing is left on a pooled thread.
     */
    private PendingDeliveries pendingDeliveries() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof PendingDeliveries pending) {
                return pending;
            }
        }
        PendingDeliveries pending = new PendingDeliveries();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    /**
     * Delivers buffered events in emission order. A plain event is sent at its position. The rolled-up
     * events of one grouped channel are folded into one roll-up, run at the position of that channel's
     * first rolled-up event. Nothing thrown here escapes: by then there is nobody to tell, and exceptions
     * from {@code afterCommit} would otherwise surface from the caller's commit.
     */
    private void runNow(List<Delivery> deliveries) {
        Set<String> rolledUp = new HashSet<>();
        for (Delivery d : deliveries) {
            try {
                if (!d.rollup()) {
                    send(d.config(), d.event());
                } else if (rolledUp.add(d.config().getId())) {
                    rollupNow(d.config().getId(), deliveries.stream()
                            .filter(o -> o.rollup() && Objects.equals(o.config().getId(), d.config().getId())).toList());
                }
            } catch (Exception e) {
                log.warn("Failed to deliver {} notification for project {}: {}",
                        d.event().getEventType(), d.event().getProjectId(), e.getMessage());
            }
        }
    }

    private void rollupNow(String configId, List<Delivery> group) {
        List<NotificationMessage> events = group.stream().map(Delivery::event).toList();
        RollupResult result = rollupService.rollup(configId, events);
        // Only the events the roll-up says definitely did not reach Discord, in their original order.
        Set<NotificationMessage> plain = Collections.newSetFromMap(new IdentityHashMap<>());
        plain.addAll(result.fallbackToPlain());
        group.stream().filter(d -> plain.contains(d.event())).forEach(d -> send(d.config(), d.event()));
    }

    private record Delivery(NotificationGroupConfig config, NotificationMessage event, boolean rollup) { }

    /** The events of one transaction, in the order they were emitted. */
    private final class PendingDeliveries implements TransactionSynchronization {

        private final List<Delivery> buffered = new ArrayList<>();
        private boolean delivered;

        void add(Delivery delivery) {
            buffered.add(delivery);
        }

        @Override
        public void afterCommit() {
            deliver();
        }

        @Override
        public void afterCompletion(int status) {
            // Registered while the commit callbacks were already running (WORKFLOW_RUN_FAILED is published
            // from an afterCommit), Spring never calls afterCommit on this one but does call this, so a
            // commit that reaches here with events still buffered delivers them now. A rollback sends
            // nothing. Either way the buffer is done with.
            if (status == STATUS_COMMITTED) {
                deliver();
            }
            buffered.clear();
        }

        private void deliver() {
            if (delivered) {
                return;
            }
            delivered = true;
            runNow(List.copyOf(buffered));
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
