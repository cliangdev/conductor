package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.repository.NotificationRollupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a notification channel to <em>one</em> Discord message while activity continues, editing it in
 * place instead of posting a message per event. Ten Posts going through review used to be about forty
 * messages; they are now one, with one line per Post showing where it stands.
 *
 * <h2>Purely event-driven</h2>
 * Nothing in here is scheduled. There is no timer, flush or sweep, which matters because production
 * scales to zero. Each incoming event either edits the channel's current message or posts a new one,
 * synchronously, and "current" is a timestamp comparison made at that moment: the message was last
 * touched less than the channel's own {@code groupWindowMinutes} ago. A quiet channel therefore just
 * stops being edited; its last message stays exactly as it was.
 *
 * <h2>Per channel</h2>
 * Each channel chooses its {@link DeliveryMode} and window in Settings. {@code EACH} declines here, so the
 * event goes out as its own message. The settings are read from the config on every event, so a change
 * takes effect on the next one with no other action.
 *
 * <h2>What rolls up</h2>
 * {@code WORK_ITEM_STATUS_CHANGED}, {@code REVIEW_SUBMITTED} and {@code REVIEWER_ASSIGNED}. Comments carry
 * their text as the content, and {@code AUTO_TRANSITION_BLOCKED}/{@code POST_AWAITING_MANUAL} ask someone
 * to act -- folding any of those into a line would hide what they are for -- so they stay separate.
 *
 * <h2>Consistency</h2>
 * Discord failures never propagate (the caller's transaction must not die because a webhook is down), and
 * a message id is only stored once Discord has actually returned one. Joins the caller's transaction
 * rather than opening its own: the publishing paths emit inside a request transaction, and a second
 * connection per event would let a burst of approvals starve the five-connection prod pool. The channel's
 * config row is locked for the rest of that transaction, so two simultaneous events cannot fork the
 * message or lose each other's line.
 */
@Service
public class NotificationRollupService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRollupService.class);

    static final Set<EventType> ROLLED_UP = EnumSet.of(
            EventType.WORK_ITEM_STATUS_CHANGED, EventType.REVIEW_SUBMITTED, EventType.REVIEWER_ASSIGNED);

    /** Past either bound the next new item starts a fresh message (Discord caps an embed at 4096 chars). */
    static final int MAX_LINES = 25;
    static final int MAX_BODY_CHARS = 3500;

    private final NotificationRollupRepository rollupRepository;
    private final DiscordProvider discordProvider;

    public NotificationRollupService(NotificationRollupRepository rollupRepository,
                                     DiscordProvider discordProvider) {
        this.rollupRepository = rollupRepository;
        this.discordProvider = discordProvider;
    }

    /** Cheap pre-check so events that never roll up do not open a transaction (the pool is small). */
    static boolean appliesTo(NotificationGroupConfig config, NotificationMessage event) {
        return ROLLED_UP.contains(event.getEventType()) && config.getProvider() == ProviderType.DISCORD
                && config.getDeliveryMode() == DeliveryMode.GROUPED;
    }

    /**
     * @return true when the event was handled here (edited into or posted as the rolling message); false
     *         when it is not a rolled-up event, the channel is not Discord or delivers each event, or
     *         nothing could be posted --
     *         the caller then sends it the ordinary way
     */
    @Transactional
    public boolean rollup(NotificationGroupConfig config, NotificationMessage event) {
        if (!appliesTo(config, event)) {
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
            if (rollupRepository.lockChannel(config.getId()).isEmpty()) {
                return false;
            }
            NotificationRollup row = rollupRepository.findById(config.getId()).orElse(null);
            boolean current = row != null && row.getUpdatedAt().plusMinutes(config.getGroupWindowMinutes()).isAfter(now);

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
                if (discordProvider.editMessage(config.getWebhookUrl(), row.getMessageId(), payload)) {
                    row.setLines(lines);
                    row.setUpdatedAt(now);
                    rollupRepository.save(row);
                    return true;
                }
                // The edit was refused -- most often because someone deleted the message. Start over
                // from this event alone rather than editing a message that is gone.
                log.info("Rollup edit failed for config {}; posting a fresh message", config.getId());
                lines = new LinkedHashMap<>();
                lines.put(workItemId, upsert(null, event, 0, workItemId));
                payload = render(event, lines);
            }

            String messageId = discordProvider.postReturningId(config.getWebhookUrl(), payload);
            if (messageId == null) {
                // Nothing was posted: leave the row as it was (never store an id Discord did not give).
                return false;
            }
            sent = true;
            NotificationRollup fresh = row != null ? row : new NotificationRollup();
            fresh.setConfigId(config.getId());
            fresh.setMessageId(messageId);
            fresh.setStartedAt(now);
            fresh.setUpdatedAt(now);
            fresh.setLines(lines);
            rollupRepository.save(fresh);
            return true;
        } catch (Exception e) {
            // A webhook or bookkeeping failure must never break the caller. If the message already went
            // out, report it as handled so the caller does not send a duplicate.
            log.warn("Failed to roll up {} notification for project {}: {}",
                    event.getEventType(), event.getProjectId(), e.getMessage());
            return sent;
        }
    }

    /** A single item renders as today's single message for that event; two or more as the roll-up embed. */
    private String render(NotificationMessage event, Map<String, RollupLine> lines) {
        if (lines.size() == 1) {
            return discordProvider.format(event);
        }
        return discordProvider.buildRollupPayload(lines.values());
    }

    private boolean overCapacity(Map<String, RollupLine> lines) {
        if (lines.size() > MAX_LINES) {
            return true;
        }
        int chars = 0;
        for (RollupLine l : lines.values()) {
            chars += DiscordProvider.lineText(l, false).length() + 1;
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
                    discordProvider.workItemLink(event.getProjectId(), meta, workItemId), null, seq);
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
        String suffix = by.isBlank() ? "" : " by " + by;
        return switch (meta.getOrDefault("verdict", "")) {
            case "APPROVED" -> "approved" + suffix;
            case "CHANGES_REQUESTED" -> "changes requested" + suffix;
            case "COMMENTED" -> "reviewed" + suffix;
            default -> null;
        };
    }
}
