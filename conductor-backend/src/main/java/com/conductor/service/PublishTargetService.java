package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import java.time.OffsetDateTime;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Where a Post can go, and where a Post is going (COND-23 T3.6).
 *
 * <h2>Available targets are derived, never stored</h2>
 * A project's selectable targets are a pure function of its ACTIVE connections, computed per request.
 * There is no registry of publishable accounts to keep in sync: connect a second Instagram account and it
 * appears; disconnect one and it stops being offered, with no migration and no stale row. The mapping is
 * one target per (platform, connection):
 *
 * <ul>
 *   <li><b>meta</b> → a {@code facebook} target for the Page, plus an {@code instagram} target
 *       <em>only</em> when the Page has a linked Instagram Business account. One connection, two
 *       destinations — which is exactly why {@code post_publish_target}'s uniqueness is on the
 *       (work item, platform, connection) triple rather than on (work item, connection).</li>
 *   <li><b>youtube</b> → a {@code youtube} target for the channel.</li>
 *   <li><b>tiktok</b> → a {@code tiktok} target for the creator.</li>
 * </ul>
 *
 * <p>A platform the project has no connection for simply does not appear — an unavailable destination is
 * absent, not disabled, because there is nothing a human could pick it for. An <em>unhealthy</em>
 * connection is the opposite case and is still offered, carrying its {@code healthStatus} and the
 * platform's own {@code healthMessage} so the picker can disable that one row and say why.
 *
 * <h2>The selection is a set-replace, not a rewrite</h2>
 * {@link #replaceSelection} diffs the incoming set against the existing rows and touches only the
 * difference. Delete-and-recreate would be catastrophic here: a row may already be
 * {@link PostPublishTargetState#HANDED_OFF} and carry the {@code platformPostId} that is the only handle
 * on a post that is already live on a platform. Dropping that row would strand the platform post beyond
 * any revocation. So an unchanged target keeps its row, its state, its platform id and its idempotency
 * key, and re-saving an identical selection writes nothing at all.
 *
 * <h2>Per-target publish options (TIK-1)</h2>
 * A selection also carries <em>how</em> the post goes out on that platform: an opaque per-platform bag of
 * option keys, stored on the row as JSON and read against the row's own {@code platform}. For TikTok it is
 * the difference between a launch video the world can see and one visible only to the creator — nothing
 * used to supply a privacy level at all, so every TikTok publish fell through to {@code SELF_ONLY} and
 * succeeded silently. {@code PublishOptionsValidator} enforces the TikTok rules at the approval gate;
 * this service only persists what was chosen.
 *
 * <p>Options are canonicalised before they are compared or stored — null and blank values dropped, keys
 * sorted — so "is this actually different from what is already there?" is a string comparison and
 * re-sending the same options in a different key order is not a change.
 *
 * <h2>The approval invariant</h2>
 * A change to the selection is a change to the publish bundle, so {@link PublishBundleGuard} runs
 * <b>first</b>, inside this transaction: it revokes any native-lane hand-off and reverts an
 * Approved-or-later Post to its review status before a single row moves. A revocation that fails throws,
 * and the selection edit rolls back with it rather than committing behind a post that is still scheduled
 * on a platform (AC-P0-1.5). The guard is invoked only when the selection actually differs, mirroring
 * {@link PublishBundleGuard#revertForCaptionOrScheduleEdit}: a client re-sending the current selection
 * unchanged must never knock a Post out of Approved.
 *
 * <p>Changing a target's options is a bundle change on exactly the same terms — what would go out is no
 * longer what was approved — so it takes the same path through the guard as adding or dropping a target.
 * An options edit is the one bundle change that keeps the row: the target still publishes to the same
 * account through the same idempotency key, so it is updated in place rather than deleted and re-created.
 */
@Service
public class PublishTargetService {

    static final String CONNECTOR_META = "meta";
    static final String CONNECTOR_YOUTUBE = "youtube";
    static final String CONNECTOR_TIKTOK = "tiktok";

    static final String PLATFORM_FACEBOOK = "facebook";
    static final String PLATFORM_INSTAGRAM = "instagram";
    static final String PLATFORM_YOUTUBE = "youtube";
    static final String PLATFORM_TIKTOK = "tiktok";

    /**
     * The non-secret identifiers each connector stores on its connection row. Duplicated as literals
     * rather than imported: the connectors' own {@code CONFIG_*} constants are package-private, and
     * widening their visibility to reach across from {@code com.conductor.service} would make an
     * internal detail of every connector part of its public surface. They are covered by this service's
     * tests, which fail loudly if a connector ever renames one.
     *
     * <p>{@code privacyLevelOptions} is the exception and is <em>not</em> duplicated here: it is read from
     * {@link PublishOptionsValidator#CONFIG_PRIVACY_LEVEL_OPTIONS}, the same package-local constant the
     * validator checks a chosen level against. A third spelling of that key would silently hand the picker
     * an empty list while the validator kept rejecting every level the picker could not offer.
     */
    private static final String CONFIG_PAGE_ID = "pageId";
    private static final String CONFIG_PAGE_NAME = "pageName";
    private static final String CONFIG_IG_ACCOUNT_ID = "instagramBusinessAccountId";
    private static final String CONFIG_IG_USERNAME = "instagramUsername";
    private static final String CONFIG_CHANNEL_ID = "channelId";
    private static final String CONFIG_CHANNEL_TITLE = "channelTitle";
    private static final String CONFIG_CREATOR_NICKNAME = "creatorNickname";
    private static final String CONFIG_CREATOR_USERNAME = "creatorUsername";

    private static final String ACTIVE = "ACTIVE";

    /** Unambiguous field delimiter for {@link #selectionKey}; see that method for why not a space or NUL. */
    private static final String KEY_DELIMITER = "\u001F";

    /** Stands in for the absent connection id of a {@link PublishLane#MANUAL} target, in keys and labels. */
    static final String MANUAL_KEY = "manual";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One place a Post could go: a platform plus the connected account that reaches it. Derived, so it
     * has no id of its own — {@code (platform, connectionId)} is its identity, and is what a selection
     * sends back.
     *
     * @param privacyLevelOptions the privacy levels this one creator may publish under, cached on the
     *                            connection at connect time; null for every non-TikTok platform and for a
     *                            TikTok connection made before they were cached. A picker cannot derive
     *                            these — TikTok reports a different set per creator — so an absent list
     *                            means "reconnect the account", never "assume the usual four" (TIK-3)
     * @param creatorNickname     the handle the creator would recognise, for the consent step's "you are
     *                            posting to @…". Null off TikTok. Deliberately not {@link #label}: the
     *                            label falls back to a connection id or display label when the account
     *                            has no name, and a consent notice that names the wrong thing is worse
     *                            than one that names nothing
     */
    /**
     * @param optionKeys the {@code publishOptions} keys this platform's targets accept — the
     *                   {@link PublishPlatform#optionParams()} whitelist, so a client can discover what a
     *                   target may carry rather than guessing from a platform name
     */
    public record TargetOption(String platform,
                               String connectorId,
                               String connectionId,
                               String label,
                               PublishLane lane,
                               String healthStatus,
                               String healthMessage,
                               List<String> privacyLevelOptions,
                               String creatorNickname,
                               List<String> optionKeys,
                               List<String> formats) {

        String key() {
            return selectionKey(platform, connectionId);
        }
    }

    /**
     * One chosen destination, as a client expresses it: where the post goes, and how it goes out there.
     *
     * @param publishOptions per-platform option keys for this target, or null/empty when nothing was chosen.
     *                       Uninterpreted here — the keys are the platform's, and only the platform's
     *                       validator and publisher read them
     * @param captionOverride copy for this destination alone; null or blank means the Post's caption. Set-
     *                        replace, like every other field here: omitting it clears an override rather
     *                        than preserving one
     * @param assetIds       an ordered subset of the Post's uploaded files this destination publishes; null
     *                       or empty means it inherits the Post's whole set, in Post order. Order matters —
     *                       Instagram crops a carousel to its first item and TikTok covers a photo post
     *                       with it
     */
    public record TargetSelection(String platform, String connectionId, Map<String, Object> publishOptions,
                                  String captionOverride, List<String> assetIds, String format) {

        /** A selection that chooses only a destination, leaving every publish option unset. */
        public TargetSelection(String platform, String connectionId) {
            this(platform, connectionId, null, null, null, null);
        }

        /** A feed-format selection: the shape every selection had before formats existed. */
        public TargetSelection(String platform, String connectionId, Map<String, Object> publishOptions,
                               String captionOverride, List<String> assetIds) {
            this(platform, connectionId, publishOptions, captionOverride, assetIds, null);
        }

        /** A selection with publish options but the Post's own caption and media. */
        public TargetSelection(String platform, String connectionId, Map<String, Object> publishOptions) {
            this(platform, connectionId, publishOptions, null, null);
        }
    }

    private final PublishPlatformRegistry platformRegistry;
    private final ConnectionRepository connectionRepository;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetAssetRepository targetAssetRepository;
    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final PublishBundleGuard publishBundleGuard;
    private final PublishTargetMediaResolver mediaResolver;

    public PublishTargetService(PublishPlatformRegistry platformRegistry,
                                ConnectionRepository connectionRepository,
                                PostPublishTargetRepository targetRepository,
                                PostPublishTargetAssetRepository targetAssetRepository,
                                AssetRepository assetRepository,
                                WorkItemRepository workItemRepository,
                                ProjectSecurityService projectSecurityService,
                                PublishBundleGuard publishBundleGuard,
                                PublishTargetMediaResolver mediaResolver) {
        this.platformRegistry = platformRegistry;
        this.connectionRepository = connectionRepository;
        this.targetRepository = targetRepository;
        this.targetAssetRepository = targetAssetRepository;
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.publishBundleGuard = publishBundleGuard;
        this.mediaResolver = mediaResolver;
    }

    /** Every target this project can currently publish to, grouped platform by platform. */
    @Transactional(readOnly = true)
    public List<TargetOption> listAvailableTargets(String projectId, User caller) {
        verifyMembership(projectId, caller);
        return deriveOptions(projectId);
    }

    /** The targets currently selected on a Work Item. */
    /**
     * Re-stamps every not-yet-dispatched target with the Work Item's current {@code scheduledFor}.
     *
     * <p>A target's {@code fireTime} is copied from the Post at selection time, so without this a Post whose
     * schedule is edited after its targets were chosen would keep firing at the old time — the schedulers read
     * {@code fireTime} off the row, never off the Work Item. Only {@code PENDING} rows are touched: a row that
     * has already been handed to the platform ({@code HANDED_OFF}) or dispatched carries platform-side state
     * that a silent re-stamp would desynchronise, and every exit from the scheduled status revokes those rows
     * anyway ({@code NativeHandoffService.unschedule}), so they are re-created rather than re-timed.
     *
     * <p>Runs in its OWN transaction ({@code REQUIRES_NEW}) and returns the number of rows re-stamped. That
     * propagation is load-bearing, not incidental: the caller ({@code WorkItemService.patchWorkItem}) hands off
     * native-lane targets immediately afterwards, and {@code NativeHandoffService} claims each row in its own
     * {@code REQUIRES_NEW} transaction. If the re-stamp wrote through the caller's transaction, the caller
     * would still hold the row lock, and the hand-off would block on a lock its own caller owns — a
     * self-deadlock that hangs the request forever rather than failing. Committing the re-stamp first
     * releases the lock before the hand-off asks for it.
     *
     * <p>The cost of the separate transaction is that a re-stamp survives a caller rollback. That is benign:
     * the row's fire time is only read once the Post is in the scheduled status, and every entry into that
     * status re-stamps again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restampFireTimes(WorkItem workItem) {
        if (workItem == null || workItem.getScheduledFor() == null) {
            return 0;
        }
        OffsetDateTime fireTime = workItem.getScheduledFor();
        int restamped = 0;
        for (PostPublishTarget target : targetRepository.findAllByWorkItemIdAndState(
                workItem.getId(), PostPublishTargetState.PENDING)) {
            if (!fireTime.isEqual(target.getFireTime() == null ? fireTime.minusYears(1) : target.getFireTime())) {
                target.setFireTime(fireTime);
                targetRepository.save(target);
                restamped++;
            }
        }
        return restamped;
    }

    @Transactional(readOnly = true)
    public List<PostPublishTarget> listSelectedTargets(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        return sorted(targetRepository.findAllByWorkItemId(workItem.getId()));
    }

    /**
     * One target plus what it will actually publish.
     *
     * <p>{@code assetIds} is this target's own selection, null when it inherits — the distinction a client
     * needs to render "Using all Post media" rather than a list that merely happens to match. {@code
     * effectiveAssetIds} and {@code effectiveCaption} are what goes out either way, so a caller showing a
     * preview never has to re-derive the inherit rule and get it subtly different.
     */
    public record TargetView(PostPublishTarget target, List<String> assetIds, List<String> effectiveAssetIds,
                             String effectiveCaption) {}

    /** {@link TargetView}s for a Post's targets, resolving all of them in a fixed number of queries. */
    @Transactional(readOnly = true)
    public List<TargetView> views(WorkItem workItem, List<PostPublishTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Map<String, PublishTargetMediaResolver.EffectiveMedia> media =
                mediaResolver.effectiveMediaByTarget(workItem.getId(), targets);
        return targets.stream()
                .map(target -> {
                    PublishTargetMediaResolver.EffectiveMedia effective = media.getOrDefault(
                            target.getId(), PublishTargetMediaResolver.EffectiveMedia.NONE);
                    return new TargetView(target,
                            target.isCustomMedia() ? effective.assetIds() : null,
                            effective.assetIds(),
                            mediaResolver.effectiveCaption(target, workItem));
                })
                .toList();
    }

    /** The Post's targets as {@link TargetView}s — what every read path returns. */
    @Transactional(readOnly = true)
    public List<TargetView> listSelectedTargetViews(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        return views(workItem, sorted(targetRepository.findAllByWorkItemId(workItem.getId())));
    }

    /**
     * One desired destination resolved against what the project can actually publish to, with its content
     * normalised: {@code captionOverride} blank-to-null, and {@code assetIds} validated against the Post's
     * own assets, de-duplicated and order-preserved. Empty {@code assetIds} means inherit.
     */
    private record DesiredTarget(TargetOption option, String optionsJson, String captionOverride,
                                 List<String> assetIds, PostFormat format) {

        boolean customMedia() {
            return !assetIds.isEmpty();
        }
    }

    /**
     * Replaces the Work Item's selection with exactly {@code selections}: creates the rows that are new,
     * deletes the rows that are gone, re-writes the publish options of a row whose options changed, and
     * leaves a wholly unchanged row completely alone.
     *
     * @throws BusinessException when a selection names a target this project cannot publish to — an
     *                           unknown connection, one owned by another project, a non-ACTIVE one, or a
     *                           platform that connection does not reach (e.g. instagram on a Page with no
     *                           linked account). Every one of those is the same generic refusal, so the
     *                           endpoint cannot be used to probe which connections exist.
     */
    @Transactional
    public List<TargetView> replaceSelection(String projectId, String workItemId,
                                            List<TargetSelection> selections, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        Map<String, TargetOption> available = new LinkedHashMap<>();
        for (TargetOption option : deriveOptions(projectId)) {
            available.put(option.key(), option);
        }

        Map<String, DesiredTarget> desired = new LinkedHashMap<>();
        for (TargetSelection selection : selections == null ? List.<TargetSelection>of() : selections) {
            String key = selectionKey(selection.platform(), selection.connectionId());
            TargetOption option = available.get(key);
            if (option == null) {
                throw new BusinessException("Not a publishable target for this project");
            }
            desired.put(key, new DesiredTarget(option, canonicalOptions(selection.publishOptions()),
                    blankToNull(selection.captionOverride()),
                    resolveAssetIds(workItem.getId(), selection.assetIds()),
                    resolveFormat(option, selection.format())));
        }

        List<PostPublishTarget> existing = targetRepository.findAllByWorkItemId(workItem.getId());
        Map<String, PostPublishTarget> existingByKey = new LinkedHashMap<>();
        for (PostPublishTarget target : existing) {
            existingByKey.put(selectionKey(target.getPlatform(), target.getConnectionId()), target);
        }

        List<PostPublishTarget> removed = existingByKey.entrySet().stream()
                .filter(entry -> !desired.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        List<DesiredTarget> added = desired.entrySet().stream()
                .filter(entry -> !existingByKey.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        // A kept row whose content changed: still the same account and the same idempotency key, but no
        // longer the same post, so it is a bundle change like any other — and an in-place update, because
        // deleting the row would strand a platform post the row is the only handle on. Options, caption and
        // media all count: each of them changes what a reviewer would be approving.
        List<PostPublishTarget> changed = existingByKey.entrySet().stream()
                .filter(entry -> desired.containsKey(entry.getKey()))
                .filter(entry -> hasContentChanged(entry.getValue(), desired.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .toList();

        if (removed.isEmpty() && added.isEmpty() && changed.isEmpty()) {
            return views(workItem, sorted(existing));
        }

        // Frozen while somebody is reading it, for the same reason the caption and media are: changing
        // where a post goes is changing what is being approved.
        publishBundleGuard.refuseTargetEditWhileFrozen(projectId, workItem);

        // Revoke and revert FIRST, in this transaction. A failed revocation throws and nothing below runs.
        publishBundleGuard.revertForBundleEdit(projectId, workItem);

        if (!removed.isEmpty()) {
            targetRepository.deleteAll(removed);
        }
        List<PostPublishTarget> kept = new ArrayList<>(existing);
        kept.removeAll(removed);

        // Updates first, then inserts, in one saveAll: the tail of the returned list is the persisted new
        // rows, which are what the response must carry.
        List<PostPublishTarget> toSave = new ArrayList<>();
        for (PostPublishTarget target : changed) {
            applyContent(target, desired.get(selectionKey(target.getPlatform(), target.getConnectionId())));
            toSave.add(target);
        }
        toSave.addAll(added.stream().map(target -> newRow(workItem, target)).toList());
        List<PostPublishTarget> saved = List.of();
        if (!toSave.isEmpty()) {
            saved = targetRepository.saveAll(toSave);
            kept.addAll(saved.subList(changed.size(), saved.size()));
        }

        // Media selections are rewritten after the targets are saved, because a new row has no id until
        // then. Deleting a target's whole selection before inserting the replacement is deliberate: a
        // reorder that swaps two positions would collide on uq_post_publish_target_asset_position if the
        // rows were updated in place, and a delete-then-insert has no such intermediate state.
        List<PostPublishTarget> withContent = new ArrayList<>(changed);
        withContent.addAll(saved.subList(Math.min(changed.size(), saved.size()), saved.size()));
        rewriteSelections(withContent, desired);

        return views(workItem, sorted(kept));
    }

    /**
     * The stable form of an options bag: null and blank values dropped, keys sorted, and an empty bag
     * reduced to {@code null}. Without it "did the options change?" would answer yes to a client that
     * re-sent the same choices with the keys in a different order — and knock the Post out of Approved for
     * nothing.
     */
    static String canonicalOptions(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        Map<String, Object> normalized = new TreeMap<>();
        options.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            normalized.put(key.trim(), value);
        });
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BusinessException("Publish options could not be stored: " + e.getMessage());
        }
    }

    /**
     * The same canonical form for what is already on the row. An unreadable stored value is returned
     * verbatim so it compares unequal to anything this service would write, and is therefore rewritten
     * into a readable one on the next save rather than left to rot.
     */
    private static String canonicalOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return canonicalOptions(MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            return json;
        }
    }

    private PostPublishTarget newRow(WorkItem workItem, DesiredTarget desired) {
        PostPublishTarget target = newRow(workItem, desired.option());
        applyContent(target, desired);
        return target;
    }

    /** Copies a desired destination's content onto its row. The join rows are written separately. */
    private void applyContent(PostPublishTarget target, DesiredTarget desired) {
        target.setPublishOptions(desired.optionsJson());
        target.setCaptionOverride(desired.captionOverride());
        target.setCustomMedia(desired.customMedia());
        target.setFormat(desired.format().name());
        // The lane follows the format: a connected account publishes most formats through the platform's
        // own scheduler, but one the platform cannot schedule (a Facebook story) is held and fired here.
        if (desired.option().lane() != PublishLane.MANUAL) {
            target.setLane(laneFor(desired.option(), desired.format()));
        }
    }

    /**
     * The format a selection asks for, checked against what the platform offers. Absent means feed, which
     * every platform publishes; anything else must be in the platform's {@code formats}.
     */
    private PostFormat resolveFormat(TargetOption option, String requested) {
        PostFormat format;
        try {
            format = PostFormat.parse(requested);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("'" + requested + "' is not a post format — use feed, reel or story");
        }
        PublishPlatform platform = platformRegistry.find(option.platform()).orElse(null);
        if (platform != null && !platform.supports(format)) {
            throw new BusinessException(platform.label() + " does not publish " + format.wire() + "s; it offers "
                    + platform.formats().stream().sorted().map(PostFormat::wire)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        return format;
    }

    private PublishLane laneFor(TargetOption option, PostFormat format) {
        return platformRegistry.find(option.platform())
                .map(platform -> platform.laneFor(format))
                .orElse(option.lane());
    }

    /**
     * Has anything about what this destination publishes changed? Any yes reverts an approved Post, so the
     * comparison has to be exact in both directions — a client re-sending identical choices must not knock
     * a Post out of Approved, and a real edit must never slip through as a no-op.
     */
    private boolean hasContentChanged(PostPublishTarget existing, DesiredTarget desired) {
        if (!Objects.equals(canonicalOptions(existing.getPublishOptions()), desired.optionsJson())) {
            return true;
        }
        if (!Objects.equals(blankToNull(existing.getCaptionOverride()), desired.captionOverride())) {
            return true;
        }
        if (existing.isCustomMedia() != desired.customMedia()) {
            return true;
        }
        if (PostFormat.parse(existing.getFormat()) != desired.format()) {
            return true;
        }
        // Only an explicit selection has stored rows to compare; two inheriting targets are equal by
        // definition and need no query.
        return desired.customMedia() && !storedAssetIds(existing.getId()).equals(desired.assetIds());
    }

    /** This target's stored selection, in order. Empty for an inheriting target. */
    private List<String> storedAssetIds(String targetId) {
        return targetAssetRepository.findAllByTargetId(targetId).stream()
                .map(PostPublishTargetAsset::getAssetId)
                .toList();
    }

    /** Replaces each target's stored selection with the one it was just given. */
    private void rewriteSelections(List<PostPublishTarget> targets, Map<String, DesiredTarget> desired) {
        for (PostPublishTarget target : targets) {
            DesiredTarget wanted = desired.get(selectionKey(target.getPlatform(), target.getConnectionId()));
            if (wanted == null) {
                continue;
            }
            targetAssetRepository.deleteAllByTargetId(target.getId());
            if (!wanted.customMedia()) {
                continue;
            }
            // Flushed before the insert so the delete lands first: without it Hibernate is free to order
            // the insert before the delete and collide on the position uniqueness constraint.
            targetAssetRepository.flush();
            List<PostPublishTargetAsset> rows = new ArrayList<>();
            List<String> assetIds = wanted.assetIds();
            for (int position = 0; position < assetIds.size(); position++) {
                rows.add(new PostPublishTargetAsset(target.getId(), assetIds.get(position), position));
            }
            targetAssetRepository.saveAll(rows);
        }
    }

    /**
     * The Post's own file assets, in the client's order, de-duplicated.
     *
     * @throws BusinessException when an id is not a file asset of this Work Item — the same generic refusal
     *                           the destination check gives, so the endpoint cannot be used to probe which
     *                           assets exist on someone else's Post
     */
    private List<String> resolveAssetIds(String workItemId, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> selectable = assetRepository.findAllByWorkItemId(workItemId).stream()
                .filter(asset -> AssetService.KIND_FILE.equals(asset.getKind()))
                .map(Asset::getId)
                .collect(Collectors.toSet());
        List<String> resolved = new ArrayList<>();
        for (String assetId : requested) {
            if (assetId == null || assetId.isBlank()) {
                continue;
            }
            if (!selectable.contains(assetId)) {
                throw new BusinessException("Not a media asset on this Post");
            }
            if (!resolved.contains(assetId)) {
                resolved.add(assetId);
            }
        }
        return List.copyOf(resolved);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PostPublishTarget newRow(WorkItem workItem, TargetOption option) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(workItem);
        target.setConnectorId(option.connectorId());
        target.setConnectionId(option.connectionId());
        target.setPlatform(option.platform());
        target.setPlatformAccountLabel(option.label());
        target.setLane(option.lane());
        target.setState(PostPublishTargetState.PENDING);
        // The Work Item's scheduled time is the source of truth for when the whole Post fires; each target
        // carries its own copy because the schedulers poll targets, not items.
        target.setFireTime(workItem.getScheduledFor());
        target.setIdempotencyKey(idempotencyKey(workItem.getId(), option));
        return target;
    }

    /**
     * The at-most-once anchor. Readable prefix for operators, random suffix for correctness: a target that
     * was published, deselected and selected again is a genuinely new publish, and reusing a deterministic
     * key would make {@code ActionInvocationService}'s claim-or-return replay the old invocation's result
     * instead of posting.
     */
    private static String idempotencyKey(String workItemId, TargetOption option) {
        return "pub:" + workItemId + ":" + option.platform() + ":"
                + (option.connectionId() == null ? MANUAL_KEY : option.connectionId())
                + ":" + UUID.randomUUID();
    }

    private List<TargetOption> deriveOptions(String projectId) {
        List<TargetOption> options = new ArrayList<>();
        for (Connection connection : activeConnections(projectId, CONNECTOR_META)) {
            Map<String, Object> config = parseConfig(connection.getConfigJson());
            if (stringValue(config, CONFIG_PAGE_ID) == null) {
                continue;
            }
            String pageName = stringValue(config, CONFIG_PAGE_NAME);
            options.add(option(connection, platformRegistry.require(PLATFORM_FACEBOOK),
                    pageName != null ? pageName : stringValue(config, CONFIG_PAGE_ID)));

            if (stringValue(config, CONFIG_IG_ACCOUNT_ID) != null) {
                String username = stringValue(config, CONFIG_IG_USERNAME);
                options.add(option(connection, platformRegistry.require(PLATFORM_INSTAGRAM),
                        username != null ? "@" + username : stringValue(config, CONFIG_IG_ACCOUNT_ID)));
            }
        }
        for (Connection connection : activeConnections(projectId, CONNECTOR_YOUTUBE)) {
            Map<String, Object> config = parseConfig(connection.getConfigJson());
            String title = stringValue(config, CONFIG_CHANNEL_TITLE);
            options.add(option(connection, platformRegistry.require(PLATFORM_YOUTUBE),
                    title != null ? title : stringValue(config, CONFIG_CHANNEL_ID)));
        }
        for (Connection connection : activeConnections(projectId, CONNECTOR_TIKTOK)) {
            Map<String, Object> config = parseConfig(connection.getConfigJson());
            String nickname = stringValue(config, CONFIG_CREATOR_NICKNAME);
            options.add(option(connection, platformRegistry.require(PLATFORM_TIKTOK),
                    nickname != null ? nickname : stringValue(config, CONFIG_CREATOR_USERNAME),
                    stringListValue(config, PublishOptionsValidator.CONFIG_PRIVACY_LEVEL_OPTIONS),
                    nickname));
        }
        // Manual destinations last, and always, one per platform the pipeline knows. Offering them even to
        // a fully connected project is deliberate rather than a fallback: a post that has to go out
        // through a personal account, a Story, or any surface a platform API does not reach still belongs
        // on the calendar, under the same review gate, with everything else. Unlike the automated options
        // these are derived from nothing and can never be absent — the lane has to work where there is no
        // connection to derive anything from.
        for (PublishPlatform platform : platformRegistry.all()) {
            options.add(new TargetOption(platform.id(), null, null, platform.manualLabel(),
                    PublishLane.MANUAL, null, null, null, null, optionKeys(platform), formats(platform)));
        }
        return List.copyOf(options);
    }

    private static TargetOption option(Connection connection, PublishPlatform platform, String label) {
        return option(connection, platform, label, null, null);
    }

    private static TargetOption option(Connection connection, PublishPlatform platform, String label,
                                       List<String> privacyLevelOptions, String creatorNickname) {
        return new TargetOption(platform.id(), connection.getConnectorId(), connection.getId(),
                label != null ? label : fallbackLabel(connection),
                platform.automatedLane(), connection.getHealthStatus(), connection.getHealthMessage(),
                privacyLevelOptions, creatorNickname, optionKeys(platform), formats(platform));
    }

    /** The formats this platform publishes, on the wire (feed, reel, story) in enum order. */
    private static List<String> formats(PublishPlatform platform) {
        return platform.formats().stream().sorted().map(PostFormat::wire).toList();
    }

    private static List<String> optionKeys(PublishPlatform platform) {
        return List.copyOf(platform.optionParams().keySet());
    }

    /** Never leave a row unlabelled: an account with no name still has to be pickable. */
    private static String fallbackLabel(Connection connection) {
        String displayLabel = connection.getDisplayLabel();
        return displayLabel != null && !displayLabel.isBlank() ? displayLabel : connection.getId();
    }

    /**
     * ACTIVE rows only, in a stable order. The DB returns rows in no defined order, and an unstable order
     * would reshuffle a picker's rows between renders.
     */
    private List<Connection> activeConnections(String projectId, String connectorId) {
        return connectionRepository.findByProjectIdAndConnectorId(projectId, connectorId).stream()
                .filter(c -> ACTIVE.equals(c.getStatus()))
                .sorted(Comparator.comparing(Connection::getId))
                .toList();
    }

    /**
     * A stable order for a set the DB returns unordered. {@code nullsFirst} is load-bearing rather than
     * defensive: a MANUAL target's connection id is null, and the natural comparator throws on null, so
     * without it every read of a Post carrying a manual destination would fail.
     */
    private static List<PostPublishTarget> sorted(List<PostPublishTarget> targets) {
        return targets.stream()
                .sorted(Comparator.comparing(PostPublishTarget::getPlatform)
                        .thenComparing(PostPublishTarget::getConnectionId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * A target's identity for diffing: the platform plus the account behind it.
     *
     * <p>The delimiter is a control character, not a space, so the two fields cannot be confused for one
     * another — no platform ending in a space can collide with a connection id starting with one. It is
     * {@code \u001F} (ASCII unit separator) rather than a literal NUL: a NUL byte in the source makes the
     * entire file binary to grep and ripgrep, which silently drop it from every search result.
     *
     * <p>A MANUAL target has no account, so it keys on the {@link #MANUAL_KEY} sentinel rather than on the
     * string "null" — one manual destination per platform, which is what the partial unique index
     * enforces in the DB.
     */
    private static String selectionKey(String platform, String connectionId) {
        return platform + KEY_DELIMITER + (connectionId == null ? MANUAL_KEY : connectionId);
    }

    private static Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String stringValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    /**
     * A cached list of strings off the connection config, or null when there is nothing usable there —
     * absent, not a list, or empty once blanks are dropped. All three are the same fact for a caller
     * ("this account's options were never cached"), and collapsing them keeps a client from telling an
     * empty list apart from a missing one and offering an empty picker as if it were a real choice.
     */
    private static List<String> stringListValue(Map<String, Object> config, String key) {
        if (!(config.get(key) instanceof List<?> values)) {
            return null;
        }
        List<String> texts = values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(text -> !text.isBlank())
                .toList();
        return texts.isEmpty() ? null : texts;
    }

    /** A non-member must not be able to tell a project apart from one that does not exist. */
    private void verifyMembership(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        return workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }
}
