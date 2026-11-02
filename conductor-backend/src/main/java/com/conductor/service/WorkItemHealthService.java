package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemHealthUpdate;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemHealthUpdateRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.workflow.lifecycle.StatechartStatus;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Health of a Work Item: an agent drafts, a person confirms. The item's current health is its latest
 * <em>confirmed</em> update; a newer unconfirmed one under {@link #DRAFT_TTL} old is a pending draft (older
 * ones are ignored on read, not deleted). See {@code docs/prd-tracking.md} "Health".
 *
 * <p>Only a person confirms: {@link #confirm} and {@link #record} (non-draft) refuse a machine credential
 * (a null {@link User}), the same line {@link ProjectSecurityService#requireMemberUser} draws.
 */
@Service
public class WorkItemHealthService {

    /** A confirmed update older than this on an in-progress item reads as stale. */
    public static final Duration STALE_AFTER = Duration.ofDays(7);
    /** An unconfirmed draft older than this is ignored. */
    public static final Duration DRAFT_TTL = Duration.ofHours(48);

    static final int MAX_NOTE = 2000;
    private static final Set<String> HEALTHS = Set.of(WorkItemHealthUpdate.ON_TRACK,
            WorkItemHealthUpdate.AT_RISK, WorkItemHealthUpdate.OFF_TRACK);

    private final WorkItemHealthUpdateRepository updateRepository;
    private final WorkItemRepository workItemRepository;
    private final WorkItemWorkflowService workflowService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public WorkItemHealthService(WorkItemHealthUpdateRepository updateRepository,
                                 WorkItemRepository workItemRepository,
                                 WorkItemWorkflowService workflowService, UserRepository userRepository) {
        this(updateRepository, workItemRepository, workflowService, userRepository, Clock.systemUTC());
    }

    WorkItemHealthService(WorkItemHealthUpdateRepository updateRepository, WorkItemRepository workItemRepository,
                          WorkItemWorkflowService workflowService, UserRepository userRepository, Clock clock) {
        this.updateRepository = updateRepository;
        this.workItemRepository = workItemRepository;
        this.workflowService = workflowService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** An unconfirmed update waiting for a person. */
    public record PendingDraft(String updateId, String health, String note, String draftedByLabel,
                               OffsetDateTime createdAt) {
    }

    /**
     * @param health    the current (latest confirmed) health, null when only a draft or a forecast risk exists
     * @param updatedAt when the current update was confirmed
     * @param stale     the current update is older than seven days while the item is in progress
     * @param atRisk    current is AT_RISK or OFF_TRACK, or stale, or the forecast overruns its date
     */
    public record Health(String health, String note, OffsetDateTime updatedAt, boolean stale,
                         PendingDraft pendingDraft, boolean atRisk) {
    }

    // --- writes ---

    /** Records an unconfirmed update for a person to confirm; attributed to {@code draftedByLabel}. */
    @Transactional
    public WorkItemHealthUpdate draft(String projectId, String itemId, String health, String note,
                                      String draftedByLabel) {
        requireItem(projectId, itemId);
        WorkItemHealthUpdate update = newUpdate(projectId, itemId, health, note);
        update.setDraftedByLabel(label(draftedByLabel));
        return updateRepository.save(update);
    }

    /**
     * Records an update that a person confirms in the same write ({@code draft=false}). A machine caller
     * (null {@code user}) gets a 403.
     */
    @Transactional
    public WorkItemHealthUpdate record(String projectId, String itemId, String health, String note, User user) {
        requirePerson(user);
        requireItem(projectId, itemId);
        WorkItemHealthUpdate update = newUpdate(projectId, itemId, health, note);
        update.setConfirmedBy(user.getId());
        update.setConfirmedAt(update.getCreatedAt());
        return updateRepository.save(update);
    }

    /**
     * A person confirms a pending draft, optionally replacing its {@code health} or {@code note}. 404 for an
     * update that is not this item's; 409 when it is already confirmed or older than {@link #DRAFT_TTL}.
     */
    @Transactional
    public WorkItemHealthUpdate confirm(String projectId, String itemId, String updateId, User user,
                                        String healthOverride, String noteOverride) {
        requirePerson(user);
        requireItem(projectId, itemId);
        WorkItemHealthUpdate update = updateRepository.findById(updateId)
                .filter(u -> itemId.equals(u.getWorkItemId()) && projectId.equals(u.getProjectId()))
                .orElseThrow(() -> new EntityNotFoundException("Health update not found"));
        if (update.isConfirmed()) {
            throw new ConflictException("That health update is already confirmed");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (update.getCreatedAt().isBefore(now.minus(DRAFT_TTL))) {
            throw new ConflictException("That draft expired after 48 hours; draft a new one");
        }
        if (healthOverride != null) {
            update.setHealth(requireHealth(healthOverride));
        }
        if (noteOverride != null) {
            update.setNote(requireNote(noteOverride));
        }
        update.setConfirmedBy(user.getId());
        update.setConfirmedAt(now);
        return updateRepository.save(update);
    }

    // --- reads ---

    /** The item's updates, newest first (at most 50), drafts included. */
    @Transactional(readOnly = true)
    public List<WorkItemHealthUpdate> history(String projectId, String itemId) {
        requireItem(projectId, itemId);
        return updateRepository.findTop50ByWorkItemIdOrderByCreatedAtDesc(itemId);
    }

    /** Display names of the people who confirmed these updates, by user id. */
    @Transactional(readOnly = true)
    public Map<String, String> confirmerNames(Collection<WorkItemHealthUpdate> updates) {
        Set<String> ids = updates.stream().map(WorkItemHealthUpdate::getConfirmedBy)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : userRepository.findAllById(ids)) {
                names.put(u.getId(), u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail());
            }
        }
        return names;
    }

    /** {@link #current(String, Collection, Map)} without a forecast (so no forecast-driven {@code atRisk}). */
    @Transactional(readOnly = true)
    public Map<String, Health> current(String projectId, Collection<WorkItem> items) {
        return current(projectId, items, Map.of());
    }

    /**
     * The health of each item that has anything to say, keyed by id, in two reads however many items there
     * are. An item with no confirmed update, no live draft and no risk is absent. {@code forecasts} (by item
     * id, possibly partial) feeds the forecast leg of {@code atRisk}: an {@code OK} forecast whose P85 (or
     * lone P50) is past the appetite end or milestone target.
     */
    @Transactional(readOnly = true)
    public Map<String, Health> current(String projectId, Collection<WorkItem> items,
                                       Map<String, Forecast> forecasts) {
        Map<String, Health> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return result;
        }
        List<String> ids = items.stream().map(WorkItem::getId).toList();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<String, WorkItemHealthUpdate> confirmed = new HashMap<>();
        updateRepository.findLatestConfirmedByWorkItem(ids).forEach(u -> confirmed.put(u.getWorkItemId(), u));
        Map<String, WorkItemHealthUpdate> drafts = new HashMap<>();
        updateRepository.findLatestDraftsSince(ids, now.minus(DRAFT_TTL))
                .forEach(u -> drafts.put(u.getWorkItemId(), u));

        StatechartCache charts = new StatechartCache(workflowService, projectId);
        for (WorkItem item : items) {
            WorkItemHealthUpdate current = confirmed.get(item.getId());
            WorkItemHealthUpdate draft = drafts.get(item.getId());
            PendingDraft pending = null;
            if (draft != null && (current == null || draft.getCreatedAt().isAfter(current.getConfirmedAt()))) {
                pending = new PendingDraft(draft.getId(), draft.getHealth(), draft.getNote(),
                        draft.getDraftedByLabel(), draft.getCreatedAt());
            }
            boolean inProgress = !charts.isTerminal(item) && charts.statusOf(item)
                    .map(StatechartStatus::category).map("in_progress"::equals).orElse(false);
            boolean stale = current != null && inProgress
                    && current.getConfirmedAt().isBefore(now.minus(STALE_AFTER));
            boolean explicitRisk = current != null && !WorkItemHealthUpdate.ON_TRACK.equals(current.getHealth());
            Forecast forecast = forecasts.get(item.getId());
            boolean forecastRisk = forecast != null && forecast.state() == ThroughputForecastService.State.OK
                    && forecast.overrunsDate();
            boolean atRisk = explicitRisk || stale || forecastRisk;
            if (current == null && pending == null && !atRisk) {
                continue;
            }
            result.put(item.getId(), new Health(current == null ? null : current.getHealth(),
                    current == null ? null : current.getNote(),
                    current == null ? null : current.getConfirmedAt(), stale, pending, atRisk));
        }
        return result;
    }

    // --- helpers ---

    private WorkItemHealthUpdate newUpdate(String projectId, String itemId, String health, String note) {
        WorkItemHealthUpdate update = new WorkItemHealthUpdate();
        update.setProjectId(projectId);
        update.setWorkItemId(itemId);
        update.setHealth(requireHealth(health));
        update.setNote(requireNote(note));
        update.setCreatedAt(OffsetDateTime.now(clock));
        return update;
    }

    private static String requireHealth(String health) {
        String value = health == null ? "" : health.trim().toUpperCase(Locale.ROOT);
        if (!HEALTHS.contains(value)) {
            throw new BusinessException("health must be ON_TRACK, AT_RISK or OFF_TRACK, got '" + health + "'");
        }
        return value;
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException("note is required");
        }
        if (note.strip().length() > MAX_NOTE) {
            throw new BusinessException("note must be at most " + MAX_NOTE + " characters");
        }
        return note.strip();
    }

    private static String label(String label) {
        String value = label == null || label.isBlank() ? "Agent" : label.strip();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static void requirePerson(User user) {
        if (user == null) {
            throw new ForbiddenException("Health is confirmed by a person; project keys and agents can only draft");
        }
    }

    private void requireItem(String projectId, String itemId) {
        boolean found = workItemRepository.findById(itemId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .isPresent();
        if (!found) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }
}
