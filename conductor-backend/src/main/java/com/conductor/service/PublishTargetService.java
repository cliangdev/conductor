package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import java.time.OffsetDateTime;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <h2>The approval invariant</h2>
 * A change to the selection is a change to the publish bundle, so {@link PublishBundleGuard} runs
 * <b>first</b>, inside this transaction: it revokes any native-lane hand-off and reverts an
 * Approved-or-later Post to its review status before a single row moves. A revocation that fails throws,
 * and the selection edit rolls back with it rather than committing behind a post that is still scheduled
 * on a platform (AC-P0-1.5). The guard is invoked only when the selection actually differs, mirroring
 * {@link PublishBundleGuard#revertForCaptionOrScheduleEdit}: a client re-sending the current selection
 * unchanged must never knock a Post out of Approved.
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One place a Post could go: a platform plus the connected account that reaches it. Derived, so it
     * has no id of its own — {@code (platform, connectionId)} is its identity, and is what a selection
     * sends back.
     */
    public record TargetOption(String platform,
                               String connectorId,
                               String connectionId,
                               String label,
                               PublishLane lane,
                               String healthStatus,
                               String healthMessage) {

        String key() {
            return selectionKey(platform, connectionId);
        }
    }

    /** One chosen destination, as a client expresses it. */
    public record TargetSelection(String platform, String connectionId) {}

    private final ConnectionRepository connectionRepository;
    private final PostPublishTargetRepository targetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final PublishBundleGuard publishBundleGuard;

    public PublishTargetService(ConnectionRepository connectionRepository,
                                PostPublishTargetRepository targetRepository,
                                WorkItemRepository workItemRepository,
                                ProjectSecurityService projectSecurityService,
                                PublishBundleGuard publishBundleGuard) {
        this.connectionRepository = connectionRepository;
        this.targetRepository = targetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.publishBundleGuard = publishBundleGuard;
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
     * <p>Returns the number of rows re-stamped. Runs in the caller's transaction.
     */
    @Transactional
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
     * Replaces the Work Item's selection with exactly {@code selections}: creates the rows that are new,
     * deletes the rows that are gone, and leaves an unchanged row completely alone.
     *
     * @throws BusinessException when a selection names a target this project cannot publish to — an
     *                           unknown connection, one owned by another project, a non-ACTIVE one, or a
     *                           platform that connection does not reach (e.g. instagram on a Page with no
     *                           linked account). Every one of those is the same generic refusal, so the
     *                           endpoint cannot be used to probe which connections exist.
     */
    @Transactional
    public List<PostPublishTarget> replaceSelection(String projectId, String workItemId,
                                                    List<TargetSelection> selections, User caller) {
        verifyMembership(projectId, caller);
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        Map<String, TargetOption> available = new LinkedHashMap<>();
        for (TargetOption option : deriveOptions(projectId)) {
            available.put(option.key(), option);
        }

        Map<String, TargetOption> desired = new LinkedHashMap<>();
        for (TargetSelection selection : selections == null ? List.<TargetSelection>of() : selections) {
            String key = selectionKey(selection.platform(), selection.connectionId());
            TargetOption option = available.get(key);
            if (option == null) {
                throw new BusinessException("Not a publishable target for this project");
            }
            desired.put(key, option);
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
        List<TargetOption> added = desired.entrySet().stream()
                .filter(entry -> !existingByKey.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (removed.isEmpty() && added.isEmpty()) {
            return sorted(existing);
        }

        // Revoke and revert FIRST, in this transaction. A failed revocation throws and nothing below runs.
        publishBundleGuard.revertForBundleEdit(projectId, workItem);

        if (!removed.isEmpty()) {
            targetRepository.deleteAll(removed);
        }
        List<PostPublishTarget> kept = new ArrayList<>(existing);
        kept.removeAll(removed);
        if (!added.isEmpty()) {
            List<PostPublishTarget> rows = added.stream().map(option -> newRow(workItem, option)).toList();
            kept.addAll(targetRepository.saveAll(rows));
        }
        return sorted(kept);
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
        return "pub:" + workItemId + ":" + option.platform() + ":" + option.connectionId()
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
            options.add(option(connection, PLATFORM_FACEBOOK, PublishLane.NATIVE,
                    pageName != null ? pageName : stringValue(config, CONFIG_PAGE_ID)));

            if (stringValue(config, CONFIG_IG_ACCOUNT_ID) != null) {
                String username = stringValue(config, CONFIG_IG_USERNAME);
                options.add(option(connection, PLATFORM_INSTAGRAM, PublishLane.APP_MANAGED,
                        username != null ? "@" + username : stringValue(config, CONFIG_IG_ACCOUNT_ID)));
            }
        }
        for (Connection connection : activeConnections(projectId, CONNECTOR_YOUTUBE)) {
            Map<String, Object> config = parseConfig(connection.getConfigJson());
            String title = stringValue(config, CONFIG_CHANNEL_TITLE);
            options.add(option(connection, PLATFORM_YOUTUBE, PublishLane.NATIVE,
                    title != null ? title : stringValue(config, CONFIG_CHANNEL_ID)));
        }
        for (Connection connection : activeConnections(projectId, CONNECTOR_TIKTOK)) {
            Map<String, Object> config = parseConfig(connection.getConfigJson());
            String nickname = stringValue(config, CONFIG_CREATOR_NICKNAME);
            options.add(option(connection, PLATFORM_TIKTOK, PublishLane.APP_MANAGED,
                    nickname != null ? nickname : stringValue(config, CONFIG_CREATOR_USERNAME)));
        }
        return List.copyOf(options);
    }

    private static TargetOption option(Connection connection, String platform, PublishLane lane, String label) {
        return new TargetOption(platform, connection.getConnectorId(), connection.getId(),
                label != null ? label : fallbackLabel(connection),
                lane, connection.getHealthStatus(), connection.getHealthMessage());
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

    private static List<PostPublishTarget> sorted(List<PostPublishTarget> targets) {
        return targets.stream()
                .sorted(Comparator.comparing(PostPublishTarget::getPlatform)
                        .thenComparing(PostPublishTarget::getConnectionId))
                .toList();
    }

    private static String selectionKey(String platform, String connectionId) {
        return platform + " " + connectionId;
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
