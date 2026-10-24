package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.NotificationRollupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a notification channel to <em>one</em> Discord message while activity continues, editing it in
 * place instead of posting a message per event. Ten Posts going through review used to be about forty
 * messages; they are now one, with one line per Post showing where it stands.
 *
 * <h2>Purely event-driven</h2>
 * Nothing in here is scheduled. There is no timer, flush, sweep, sleep or background thread, which matters
 * because production scales to zero. Each incoming event either edits the channel's current message or
 * posts a new one, synchronously, and "current" is a timestamp comparison made at that moment: the message
 * was last touched less than the channel's own {@code groupWindowMinutes} ago, and was started less than
 * four windows (at most four hours) ago, so steady activity cannot keep editing one message forever. A quiet
 * channel therefore just stops being edited; its last message stays exactly as it was.
 *
 * <h2>Per channel</h2>
 * Each channel chooses its {@link DeliveryMode} and window in Settings. {@code EACH} declines here, so the
 * event goes out as its own message. The settings are re-read from the config <em>after</em> the channel
 * lock is taken, so a change made while an event was waiting is honoured by that event.
 *
 * <h2>What rolls up</h2>
 * {@code WORK_ITEM_STATUS_CHANGED}, {@code REVIEW_SUBMITTED} and {@code REVIEWER_ASSIGNED}. Comments carry
 * their text as the content, and {@code AUTO_TRANSITION_BLOCKED}/{@code POST_AWAITING_MANUAL} ask someone
 * to act -- folding any of those into a line would hide what they are for -- so they stay separate.
 *
 * <h2>Transactions</h2>
 * The caller invokes this <em>after its own transaction has committed</em> (see
 * {@link NotificationDeliveryService}), and it runs in a short transaction of its own. It never joins the
 * caller's: joining would hold the channel's row lock for the rest of a possibly long caller transaction
 * (and could deadlock with lifecycle cascades that update parent Work Items after the notification ran),
 * and a database error swallowed in here would still mark the caller rollback-only. The price is that two
 * connections are held at once, but only briefly and with no locks held by the first -- the caller's work
 * is already committed -- so a burst of approvals cannot starve the five-connection prod pool. The lock
 * wait is capped ({@code lock_timeout}) and every Discord call has a short timeout, so a stuck holder or a
 * stalled Discord costs at most a few seconds and, at worst, this one notification.
 *
 * <h2>Consistency</h2>
 * Discord failures never propagate, and a message id is only stored once Discord has actually returned
 * one. The channel's config row is locked for the transaction, so two simultaneous events cannot fork the
 * message or lose each other's line. A failed or rate-limited edit keeps the existing message id and still
 * saves the updated lines, so the next successful edit carries everything; only a 404 (the message was
 * deleted) posts a fresh message, and that one carries all the current lines.
 */
@Service
public class NotificationRollupService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRollupService.class);

    static final Set<EventType> ROLLED_UP = EnumSet.of(
            EventType.WORK_ITEM_STATUS_CHANGED, EventType.REVIEW_SUBMITTED, EventType.REVIEWER_ASSIGNED);

    /** Past either bound the next new item starts a fresh message (Discord caps an embed at 4096 chars). */
    static final int MAX_LINES = 25;
    static final int MAX_BODY_CHARS = 3500;

    /** One message is edited for at most this many windows, and never for longer than {@link #MAX_MESSAGE_AGE}. */
    static final int MAX_AGE_IN_WINDOWS = 4;
    static final Duration MAX_MESSAGE_AGE = Duration.ofHours(4);

    private static final int MAX_REVIEWER_NAME = 80;

    private final NotificationRollupRepository rollupRepository;
    private final NotificationGroupConfigRepository configRepository;
    private final DiscordProvider discordProvider;

    public NotificationRollupService(NotificationRollupRepository rollupRepository,
                                     NotificationGroupConfigRepository configRepository,
                                     DiscordProvider discordProvider) {
        this.rollupRepository = rollupRepository;
        this.configRepository = configRepository;
        this.discordProvider = discordProvider;
    }

    /** Cheap pre-check so events that never roll up do not open a transaction (the pool is small). */
    static boolean appliesTo(NotificationGroupConfig config, NotificationMessage event) {
        return ROLLED_UP.contains(event.getEventType()) && config.getProvider() == ProviderType.DISCORD
                && config.getDeliveryMode() == DeliveryMode.GROUPED;
    }

    /**
     * @return true when the event is dealt with and nothing more should be sent (edited into or posted as
     *         the rolling message, or deliberately dropped because the channel was deleted or disabled, no
     *         longer wants this event, or Discord is rate-limiting us); false when the caller should send
     *         it the ordinary way -- not a rolled-up event, the channel now delivers each event, or
     *         nothing could be posted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rollup(String configId, NotificationMessage event) {
        if (!ROLLED_UP.contains(event.getEventType())) {
            return false;
        }
        Map<String, String> meta = event.getMetadata();
        String workItemId = meta.get("workItemId");
        if (workItemId == null || workItemId.isBlank()) {
            return false;
        }

        boolean sent = false;
        try {
            OffsetDateTime now = OffsetDateTime.now();
            // Bound how long we wait for the lock; a stuck holder then costs this notification, not a
            // connection for as long as the holder lives.
            rollupRepository.limitLockWait();
            if (rollupRepository.lockChannel(configId).isEmpty()) {
                return true; // the channel was deleted
            }
            // Read after the lock: whatever changed the channel's settings while we waited is now visible.
            NotificationGroupConfig config = configRepository.findById(configId).orElse(null);
            if (config == null || !config.isEnabled()
                    || !config.getEnabledEventTypes().contains(event.getEventType().name())) {
                return true; // disabled, or no longer opted in: drop
            }
            if (!appliesTo(config, event)) {
                return false; // now EACH: the caller sends it plainly
            }

            NotificationRollup row = rollupRepository.findById(configId).orElse(null);
            boolean current = row != null && isCurrent(row, config, now);

            Map<String, RollupLine> lines = new LinkedHashMap<>(current ? row.getLines() : Map.of());
            boolean isNewItem = !lines.containsKey(workItemId);
            lines.put(workItemId, upsert(lines.get(workItemId), event, nextSeq(lines), workItemId));

            if (current && isNewItem && lines.size() > 1 && overCapacity(lines)) {
                // Full: this item opens a new message rather than lengthening one that is about to hit
                // Discord's limit.
                lines = new LinkedHashMap<>();
                lines.put(workItemId, upsert(null, event, 0, workItemId));
                current = false;
            }

            String payload = render(event, lines);

            if (current) {
                switch (discordProvider.editMessage(config.getWebhookUrl(), row.getMessageId(), payload)) {
                    case OK -> {
                        row.setLines(lines);
                        row.setUpdatedAt(now);
                        rollupRepository.save(row);
                        return true;
                    }
                    case RATE_LIMITED, FAILED -> {
                        // The message still exists, so keep its id and keep the lines: the next
                        // successful edit then includes everything. No retry and no extra send -- piling
                        // on a rate-limited or failing webhook would only make it worse.
                        row.setLines(lines);
                        row.setUpdatedAt(now);
                        rollupRepository.save(row);
                        return true;
                    }
                    case NOT_FOUND -> log.info("Rollup message for config {} was deleted; posting a fresh one",
                            configId);
                }
                // Deleted: post a new message carrying every current line, not just this event's.
            }

            DiscordProvider.PostResult posted = discordProvider.postReturningId(config.getWebhookUrl(), payload);
            if (posted.rateLimited()) {
                if (row != null) {
                    // Remember the lines against the old id; the next event edits (404s) and reposts them.
                    row.setLines(lines);
                    row.setUpdatedAt(now);
                    rollupRepository.save(row);
                }
                return true;
            }
            if (posted.messageId() == null) {
                // Nothing was posted: leave the row as it was (never store an id Discord did not give).
                return false;
            }
            sent = true;
            NotificationRollup fresh = row != null ? row : new NotificationRollup();
            fresh.setConfigId(configId);
            fresh.setMessageId(posted.messageId());
            fresh.setStartedAt(now);
            fresh.setUpdatedAt(now);
            fresh.setLines(lines);
            rollupRepository.save(fresh);
            return true;
        } catch (Exception e) {
            // A webhook, lock or bookkeeping failure must never escape. If the message already went out,
            // report it as handled so the caller does not send a duplicate. A failed statement leaves a
            // Postgres transaction unusable, so roll this (own) transaction back rather than try to commit.
            log.warn("Failed to roll up {} notification for project {}: {}",
                    event.getEventType(), event.getProjectId(), e.getMessage());
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (RuntimeException ignored) {
                // no surrounding transaction (unit tests)
            }
            return sent;
        }
    }

    /** Edited recently enough, and started recently enough: see the age cap in the class javadoc. */
    private static boolean isCurrent(NotificationRollup row, NotificationGroupConfig config, OffsetDateTime now) {
        long window = config.getGroupWindowMinutes();
        Duration maxAge = Duration.ofMinutes(window * MAX_AGE_IN_WINDOWS);
        if (maxAge.compareTo(MAX_MESSAGE_AGE) > 0) {
            maxAge = MAX_MESSAGE_AGE;
        }
        return row.getUpdatedAt().plusMinutes(window).isAfter(now)
                && row.getStartedAt().plus(maxAge).isAfter(now);
    }

    /**
     * Two or more items render as the roll-up embed. One item renders as today's single message for the
     * latest event -- unless it has both moved and been given a verdict, when the latest event alone would
     * hide the other half (an approval, then an auto-move to Scheduled, must not read as just "moved"), so
     * it is rendered from the item's whole line.
     */
    private String render(NotificationMessage event, Map<String, RollupLine> lines) {
        if (lines.size() == 1) {
            RollupLine only = lines.values().iterator().next();
            if (only.moved() && only.verdictNote() != null && !only.verdictNote().isBlank()) {
                return discordProvider.buildSinglePayload(only);
            }
            return discordProvider.format(event);
        }
        return discordProvider.buildRollupPayload(lines.values());
    }

    private boolean overCapacity(Map<String, RollupLine> lines) {
        if (lines.size() > MAX_LINES) {
            return true;
        }
        int chars = 0;
        Set<String> headers = new HashSet<>();
        for (RollupLine l : lines.values()) {
            chars += DiscordProvider.lineText(l, false).length() + 1;
            String label = l.statusLabel() == null || l.statusLabel().isBlank() ? "Updated" : l.statusLabel();
            if (headers.add(l.status() == null ? "" : l.status())) {
                chars += label.length() + 6; // "**label**\n" and the blank line between groups
            }
        }
        return chars > MAX_BODY_CHARS;
    }

    private static long nextSeq(Map<String, RollupLine> lines) {
        return lines.values().stream().mapToLong(RollupLine::seq).max().orElse(-1) + 1;
    }

    /**
     * Folds an event into the item's line. A status change replaces the state; a verdict only sets the
     * note (and the state only if the item is new here); a reviewer assignment only adds the item. The
     * verdict note is never cleared by a later status change, so one approval stays one line.
     */
    private RollupLine upsert(RollupLine existing, NotificationMessage event, long seq, String workItemId) {
        Map<String, String> meta = event.getMetadata();
        EventType type = event.getEventType();
        String title = meta.getOrDefault("workItemTitle", workItemId);

        RollupLine line = existing;
        if (line == null) {
            String noun = meta.getOrDefault("noun", "Work Item");
            boolean isStatusChange = type == EventType.WORK_ITEM_STATUS_CHANGED;
            String status = isStatusChange ? meta.get("toStatus") : meta.get("status");
            String label = isStatusChange ? meta.get("toStatusLabel") : meta.get("statusLabel");
            String category = isStatusChange ? meta.get("toCategory") : meta.get("statusCategory");
            if ((label == null || label.isBlank()) && status != null) {
                label = DiscordProvider.humanize(status);
            }
            line = new RollupLine(workItemId, meta.get("displayId"), title, noun, status, label, category,
                    discordProvider.workItemLink(event.getProjectId(), meta, workItemId), null, seq,
                    isStatusChange);
        } else {
            line = line.withTitle(title);
        }

        if (existing != null && type == EventType.WORK_ITEM_STATUS_CHANGED) {
            String status = meta.get("toStatus");
            String label = meta.get("toStatusLabel");
            if (label == null || label.isBlank()) {
                label = DiscordProvider.humanize(status);
            }
            line = line.withState(status, label, meta.get("toCategory"));
        }
        if (type == EventType.REVIEW_SUBMITTED) {
            line = line.withVerdictNote(verdictNote(meta));
        }
        return line;
    }

    private static String verdictNote(Map<String, String> meta) {
        String by = meta.getOrDefault("reviewerName", "");
        if (by.length() > MAX_REVIEWER_NAME) {
            by = by.substring(0, MAX_REVIEWER_NAME - 3) + "...";
        }
        String suffix = by.isBlank() ? "" : " by " + by;
        return switch (meta.getOrDefault("verdict", "")) {
            case "APPROVED" -> "approved" + suffix;
            case "CHANGES_REQUESTED" -> "changes requested" + suffix;
            case "COMMENTED" -> "reviewed" + suffix;
            default -> null;
        };
    }
}
