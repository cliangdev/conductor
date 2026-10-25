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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The caller invokes this <em>after its own transaction has committed</em>, so a change that rolled back
 * sends nothing, to this channel or any other (see
 * {@link NotificationDeliveryService}), and it runs in a short transaction of its own. It never joins the
 * caller's: joining would hold the channel's row lock for the rest of a possibly long caller transaction
 * (and could deadlock with lifecycle cascades that update parent Work Items after the notification ran),
 * and a database error swallowed in here would still mark the caller rollback-only. The price is that two
 * connections are held at once, but only briefly and with no locks held by the first -- the caller's work
 * is already committed -- so a burst of approvals cannot starve the five-connection prod pool. The lock
 * wait is capped ({@code lock_timeout}) and every Discord call has a short timeout, so a stuck holder or a
 * stalled Discord costs at most a few seconds and, at worst, this one notification.
 *
 * <h2>Batches</h2>
 * One caller transaction can emit several rolled-up events for the same channel; they arrive together and
 * are folded in order under one lock (see {@link #rollup(String, List)}). A batch that does not fit in one
 * message is split into chunks, each under the line and character caps: the current message is finished
 * with one edit and each further chunk is one new post.
 *
 * <h2>At most once, never silently lost</h2>
 * A message id is only stored once Discord has returned one, and the row is only ever written with lines
 * for the message id that was returned for those lines. The channel's config row is locked for the
 * transaction, so two simultaneous events cannot fork the message or lose each other's line.
 * <ul>
 *   <li>A rate-limited <em>edit</em> of the current message keeps its id and saves the lines, so the next
 *       successful edit carries everything. A rate-limited <em>post</em> (or closing edit) writes nothing:
 *       the events are dropped with a warning and nothing else is sent to the throttled webhook.</li>
 *   <li>A 404 or any other failed edit abandons that message and posts a fresh one with every current
 *       line. If that post definitely failed, the affected events go back to the caller for a plain send
 *       (see {@link RollupResult}).</li>
 *   <li>An ambiguous post (timeout, 5xx, unparsable reply) may have created the message, so it is dropped
 *       with a warning and never re-sent: a missing line is repaired by the next event, a duplicate is not.</li>
 * </ul>
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

    /** One chunk of a batch: the lines of one Discord message, and the events whose lines landed in it. */
    private static final class Chunk {
        final Map<String, RollupLine> lines;
        final List<NotificationMessage> events = new ArrayList<>();
        NotificationMessage latest;

        Chunk(Map<String, RollupLine> lines) {
            this.lines = lines;
        }
    }

    /**
     * Folds every event of one caller transaction for this channel into the rolling message with a single
     * lock and a single read of the settings and the row. Approving a Post is a review, then Approved,
     * then Scheduled; doing that as three roll-ups was three transactions and three round trips (and three
     * hits on the webhook's rate limit) for one visible change.
     *
     * <p>If folding fills the current message the batch is split into chunks that each stay under the
     * caps -- as many as it takes, so it can roll over more than once. The current message is finished
     * with one edit (skipped when the batch changed nothing in it) and each further chunk is one post,
     * strictly in order. Nothing is sent after a rate limit or an ambiguous post.
     *
     * @return what the caller must still send plainly (see {@link RollupResult}): all of the events when
     *         they are not rolled-up events or the channel now delivers each event; the events whose lines
     *         were in a chunk that definitely failed to post; none otherwise
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RollupResult rollup(String configId, List<NotificationMessage> events) {
        if (events.isEmpty()) {
            return RollupResult.handled();
        }
        for (NotificationMessage event : events) {
            String id = event.getMetadata().get("workItemId");
            if (!ROLLED_UP.contains(event.getEventType()) || id == null || id.isBlank()) {
                return RollupResult.fallback(events); // never mixed in practice; if so, all go out plainly
            }
        }

        boolean touchedDiscord = false;
        try {
            OffsetDateTime now = OffsetDateTime.now();
            // Bound how long we wait for the lock; a stuck holder then costs this notification, not a
            // connection for as long as the holder lives.
            rollupRepository.limitLockWait();
            if (rollupRepository.lockChannel(configId).isEmpty()) {
                return RollupResult.handled(); // the channel was deleted
            }
            // Read after the lock: whatever changed the channel's settings while we waited is now visible.
            NotificationGroupConfig config = configRepository.findById(configId).orElse(null);
            if (config == null || !config.isEnabled()) {
                return RollupResult.handled(); // deleted or disabled: drop
            }
            List<NotificationMessage> wanted = events.stream()
                    .filter(e -> config.getEnabledEventTypes().contains(e.getEventType().name()))
                    .toList();
            if (wanted.isEmpty()) {
                return RollupResult.handled(); // no longer opted in: drop
            }
            if (!appliesTo(config, wanted.get(0))) {
                return RollupResult.fallback(events); // now EACH: the caller sends them plainly
            }

            NotificationRollup row = rollupRepository.findById(configId).orElse(null);
            boolean current = row != null && isCurrent(row, config, now);
            String webhook = config.getWebhookUrl();

            List<Chunk> chunks = fold(wanted, current ? row.getLines() : Map.of());

            for (int k = 0; k < chunks.size(); k++) {
                Chunk chunk = chunks.get(k);
                boolean last = k == chunks.size() - 1;
                boolean editFailed = false;

                if (k == 0 && current) {
                    if (chunk.events.isEmpty()) {
                        continue; // the batch changed nothing in it; the message already shows these lines
                    }
                    String payload = render(chunk); // before the flag: a render failure sent nothing
                    touchedDiscord = true;
                    DiscordProvider.EditOutcome outcome =
                            discordProvider.editMessage(webhook, row.getMessageId(), payload);
                    switch (outcome) {
                        case OK -> {
                            row.setLines(chunk.lines);
                            if (last) {
                                row.setUpdatedAt(now);
                            }
                            rollupRepository.save(row);
                            continue;
                        }
                        case RATE_LIMITED -> {
                            if (last) {
                                // The message exists and is the one these lines belong to: keep its id and
                                // save the lines, so the next successful edit carries everything.
                                row.setLines(chunk.lines);
                                row.setUpdatedAt(now);
                                rollupRepository.save(row);
                                return RollupResult.handled();
                            }
                            // A closing edit that did not land: posting its successors would put the
                            // batch out of order behind an unfinished message. Stop; write nothing.
                            log.warn("Discord rate-limited finishing the rollup message for config {}; "
                                    + "dropping {} notification(s) for project {}", configId,
                                    countEvents(chunks, k), wanted.get(0).getProjectId());
                            return RollupResult.handled();
                        }
                        case NOT_FOUND -> {
                            log.info("Rollup message for config {} was deleted; posting a fresh one", configId);
                            editFailed = true;
                        }
                        case FAILED -> {
                            // Do not keep the window open on a message we cannot edit, with every later
                            // event repeating the same failing PATCH: abandon it and post fresh.
                            log.warn("Editing the rollup message for config {} failed; posting a fresh one",
                                    configId);
                            editFailed = true;
                        }
                    }
                }

                String body = render(chunk);
                touchedDiscord = true;
                DiscordProvider.PostResult posted = discordProvider.postReturningId(webhook, body);
                switch (posted.outcome()) {
                    case POSTED -> {
                        NotificationRollup target = row != null ? row : new NotificationRollup();
                        target.setConfigId(configId);
                        target.setMessageId(posted.messageId());
                        target.setStartedAt(now);
                        target.setUpdatedAt(now);
                        target.setLines(chunk.lines);
                        rollupRepository.save(target);
                        row = target;
                    }
                    case RATE_LIMITED -> {
                        // Row untouched: never record lines against a message id that was not returned
                        // for them. And nothing more to a webhook that is refusing us.
                        log.warn("Discord rate-limited a rollup post for config {} (see retry_after above); "
                                + "dropping {} notification(s) for project {}", configId,
                                countEvents(chunks, k), wanted.get(0).getProjectId());
                        return RollupResult.handled();
                    }
                    case AMBIGUOUS -> {
                        // It may have been created. Sending again could duplicate it; the next event will
                        // repair the rolling message.
                        log.warn("Outcome of a rollup post for config {} is unknown; not re-sending "
                                + "{} notification(s) for project {}", configId, countEvents(chunks, k),
                                wanted.get(0).getProjectId());
                        return RollupResult.handled();
                    }
                    case DEFINITE_FAILURE -> {
                        if (editFailed && row != null) {
                            rollupRepository.delete(row); // abandon the message we could not edit
                        }
                        Set<NotificationMessage> unsent = Collections.newSetFromMap(new IdentityHashMap<>());
                        for (int i = k; i < chunks.size(); i++) {
                            unsent.addAll(chunks.get(i).events);
                        }
                        log.warn("Posting the rollup message for config {} failed; sending {} notification(s) "
                                + "plainly", configId, unsent.size());
                        return RollupResult.fallback(wanted.stream().filter(unsent::contains).toList());
                    }
                }
            }
            return RollupResult.handled();
        } catch (Exception e) {
            // A webhook, lock or bookkeeping failure must never escape. If Discord may already have been
            // told something, report it as handled so the caller does not send a duplicate. A failed
            // statement leaves a Postgres transaction unusable, so roll this (own) transaction back rather
            // than try to commit.
            NotificationMessage first = events.get(0);
            log.warn("Failed to roll up {} notification(s) for project {}: {}",
                    first.getEventType(), first.getProjectId(), e.getMessage());
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (RuntimeException ignored) {
                // no surrounding transaction (unit tests)
            }
            return touchedDiscord ? RollupResult.handled() : RollupResult.fallback(events);
        }
    }

    private static int countEvents(List<Chunk> chunks, int from) {
        int n = 0;
        for (int i = from; i < chunks.size(); i++) {
            n += chunks.get(i).events.size();
        }
        return n;
    }

    /**
     * Applies the batch's events, in order, to the channel's lines and splits the result into messages.
     * The first chunk starts from {@code existing} (the current message's lines, or none); a new item that
     * would push the last chunk past the caps opens the next one. An item seen again after its chunk was
     * closed carries its latest state into the chunk it now appears in.
     */
    private List<Chunk> fold(List<NotificationMessage> wanted, Map<String, RollupLine> existing) {
        List<Chunk> chunks = new ArrayList<>();
        Chunk cur = new Chunk(new LinkedHashMap<>(existing));
        chunks.add(cur);
        Map<String, RollupLine> latestInBatch = new HashMap<>();
        Set<String> noteThisBatch = new HashSet<>();

        for (NotificationMessage event : wanted) {
            String workItemId = event.getMetadata().get("workItemId");
            boolean isNewHere = !cur.lines.containsKey(workItemId);
            RollupLine before = cur.lines.get(workItemId);
            if (before == null) {
                before = latestInBatch.get(workItemId); // closed in an earlier chunk of this batch
            }
            RollupLine updated = upsert(before, event, nextSeq(cur.lines), workItemId, noteThisBatch);

            if (isNewHere && !cur.lines.isEmpty()) {
                Map<String, RollupLine> candidate = new LinkedHashMap<>(cur.lines);
                candidate.put(workItemId, updated);
                if (overCapacity(candidate)) {
                    // Full: this item opens a new message rather than lengthening one about to hit
                    // Discord's limit.
                    cur = new Chunk(new LinkedHashMap<>());
                    chunks.add(cur);
                }
            }
            if (isNewHere) {
                updated = updated.withSeq(nextSeq(cur.lines));
            }
            cur.lines.put(workItemId, updated);
            latestInBatch.put(workItemId, updated);
            cur.events.add(event);
            cur.latest = event;
        }
        return chunks;
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
     * it is rendered from the item's whole line. That is also how a batch that collapses to one item shows
     * the final state plus the verdict note. {@code latest} is null only for a message finished by a
     * rollover before any event of the batch touched it.
     */
    private String render(Chunk chunk) {
        Map<String, RollupLine> lines = chunk.lines;
        NotificationMessage latest = chunk.latest;
        if (lines.size() == 1) {
            RollupLine only = lines.values().iterator().next();
            boolean combined = only.moved() && only.verdictNote() != null && !only.verdictNote().isBlank();
            if (combined || latest == null) {
                return discordProvider.buildSinglePayload(only);
            }
            return discordProvider.format(latest);
        }
        return discordProvider.buildRollupPayload(lines.values());
    }

    /** Over either cap, measured on the body the renderer would actually produce (never a copy of it). */
    private static boolean overCapacity(Map<String, RollupLine> lines) {
        return lines.size() > MAX_LINES || DiscordProvider.rollupBody(lines.values()).length() > MAX_BODY_CHARS;
    }

    private static long nextSeq(Map<String, RollupLine> lines) {
        return lines.values().stream().mapToLong(RollupLine::seq).max().orElse(-1) + 1;
    }

    /**
     * Folds an event into the item's line. A status change replaces the state (and keeps the latest PR link
     * and assignee it carries); a verdict or a reviewer assignment sets the note (and the state only if the
     * item is new here). The note is news for one batch: a status change keeps it only if it was set in the
     * same batch (an approval and its auto-chain share a transaction), and clears it otherwise.
     */
    private RollupLine upsert(RollupLine existing, NotificationMessage event, long seq, String workItemId,
                              Set<String> noteThisBatch) {
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

        switch (type) {
            case WORK_ITEM_STATUS_CHANGED -> {
                if (existing != null) {
                    String status = meta.get("toStatus");
                    String label = meta.get("toStatusLabel");
                    if (label == null || label.isBlank()) {
                        label = DiscordProvider.humanize(status);
                    }
                    line = line.withState(status, label, meta.get("toCategory"));
                    if (!noteThisBatch.contains(workItemId)) {
                        line = line.withVerdictNote(null); // stale: from an earlier batch
                    }
                }
                line = line.withExtras(meta.get("prUrl"), meta.get("assigneeName"));
            }
            case REVIEW_SUBMITTED -> {
                line = line.withVerdictNote(verdictNote(meta));
                noteThisBatch.add(workItemId);
            }
            case REVIEWER_ASSIGNED -> {
                String by = DiscordProvider.cleanName(meta.getOrDefault("reviewerName", ""));
                line = line.withVerdictNote(by.isEmpty() ? null : "reviewer: " + by);
                noteThisBatch.add(workItemId);
            }
            default -> { }
        }
        return line;
    }

    private static String verdictNote(Map<String, String> meta) {
        String by = DiscordProvider.cleanName(meta.getOrDefault("reviewerName", ""));
        String suffix = by.isEmpty() ? "" : " by " + by;
        return switch (meta.getOrDefault("verdict", "")) {
            case "APPROVED" -> "approved" + suffix;
            case "CHANGES_REQUESTED" -> "changes requested" + suffix;
            case "COMMENTED" -> "reviewed" + suffix;
            default -> null;
        };
    }
}
