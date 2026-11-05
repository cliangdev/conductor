package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.repository.BlockerRow;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemDependencyRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.workflow.lifecycle.Statechart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The signals derived from a Work Item's facts, computed on read for a whole page at once -- see
 * {@code docs/prd-tracking.md} "Derived signals". Nothing is stored and nothing runs periodically.
 *
 * <ul>
 *   <li>{@code blocked}: at least one {@code BLOCKS} blocker whose status is not terminal in <em>its own</em>
 *       Workflow. A cancelled or closed blocker unblocks.
 *   <li>{@code ready}: not blocked, status category {@code open}, not terminal. Assignee is irrelevant.
 *   <li>{@code stale}: status category {@code in_progress}, not updated for {@link #STALE_AFTER}, and no
 *       agent session {@code WORKING} with a heartbeat inside that window.
 *   <li>{@code needsInput}: the item's latest open agent session is {@code NEEDS_INPUT} -- for an item with
 *       children, any descendant's too, so a PRD shows that something beneath it is waiting for a person.
 * </ul>
 *
 * <p>Cost: one query for the blockers, one for the open sessions, one for the child counts, and -- only for
 * items that have children -- one batched subtree walk for all of them to find descendants. Statecharts are
 * resolved once per {@code workflow@version}. The query count is fixed however long the page is.
 */
@Service
public class WorkItemSignalService {

    /** How long an in-progress item may sit untouched, with no live agent on it, before it reads as stale. */
    public static final Duration STALE_AFTER = Duration.ofDays(7);

    private static final String CATEGORY_OPEN = "open";
    private static final String CATEGORY_IN_PROGRESS = "in_progress";

    private final WorkItemRepository workItemRepository;
    private final WorkItemDependencyRepository dependencyRepository;
    private final WorkItemAgentSessionRepository sessionRepository;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final Clock clock;

    @Autowired
    public WorkItemSignalService(WorkItemRepository workItemRepository,
                                 WorkItemDependencyRepository dependencyRepository,
                                 WorkItemAgentSessionRepository sessionRepository,
                                 WorkItemWorkflowService workItemWorkflowService) {
        this(workItemRepository, dependencyRepository, sessionRepository, workItemWorkflowService,
                Clock.systemUTC());
    }

    WorkItemSignalService(WorkItemRepository workItemRepository,
                          WorkItemDependencyRepository dependencyRepository,
                          WorkItemAgentSessionRepository sessionRepository,
                          WorkItemWorkflowService workItemWorkflowService, Clock clock) {
        this.workItemRepository = workItemRepository;
        this.dependencyRepository = dependencyRepository;
        this.sessionRepository = sessionRepository;
        this.workItemWorkflowService = workItemWorkflowService;
        this.clock = clock;
    }

    /** A blocker still holding an item up. */
    public record Blocker(String id, String displayId, String title) {
    }

    /**
     * @param blockedBy     the blockers that have not finished (empty unless {@code blocked})
     * @param agentActivity the item's own latest open agent session, if any (not its descendants')
     */
    public record Signals(boolean blocked, List<Blocker> blockedBy, boolean ready, boolean stale,
                          boolean needsInput, WorkItemAgentSession agentActivity) {
    }

    @Transactional(readOnly = true)
    public Map<String, Signals> signalsFor(String projectId, List<WorkItem> items) {
        Map<String, Signals> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return result;
        }
        List<String> ids = items.stream().map(WorkItem::getId).toList();
        StatechartCache charts = new StatechartCache(workItemWorkflowService, projectId);

        // Blockers: one query; a blocker blocks only while it is not terminal in its own Workflow.
        Map<String, List<Blocker>> stillBlockedBy = new HashMap<>();
        for (BlockerRow row : dependencyRepository.findBlockersFor(ids)) {
            if (!charts.isTerminal(row.blockerWorkflow(), row.blockerWorkflowVersion(), row.blockerStatus())) {
                stillBlockedBy.computeIfAbsent(row.blockedId(), k -> new ArrayList<>())
                        .add(new Blocker(row.blockerId(), row.blockerDisplayId(), row.blockerTitle()));
            }
        }

        // Descendants of the items that have children, so a PRD can report a task asking for input.
        Map<String, Integer> childCounts = new HashMap<>();
        for (Object[] row : workItemRepository.countChildrenGrouped(ids)) {
            childCounts.put((String) row[0], ((Number) row[1]).intValue());
        }
        Map<String, List<String>> descendants = new HashMap<>();
        Set<String> sessionIds = new HashSet<>(ids);
        List<String> parents = ids.stream().filter(id -> childCounts.getOrDefault(id, 0) > 0).toList();
        if (!parents.isEmpty()) {
            for (Object[] row : workItemRepository.findSubtreeIdsFor(parents)) {
                String rootId = (String) row[0];
                String id = (String) row[1];
                if (!rootId.equals(id)) {
                    descendants.computeIfAbsent(rootId, k -> new ArrayList<>()).add(id);
                    sessionIds.add(id);
                }
            }
        }

        // Open sessions: one query for the page and every descendant.
        Map<String, WorkItemAgentSession> latestOpen = new HashMap<>();
        for (WorkItemAgentSession s : sessionRepository.findLatestOpenByWorkItem(sessionIds)) {
            latestOpen.put(s.getWorkItemId(), s);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime staleBefore = now.minus(STALE_AFTER);
        for (WorkItem item : items) {
            Optional<String> category = charts.statusOf(item).map(s -> s.category());
            boolean terminal = charts.isTerminal(item);
            List<Blocker> blockers = stillBlockedBy.getOrDefault(item.getId(), List.of());
            boolean blocked = !blockers.isEmpty();
            WorkItemAgentSession own = latestOpen.get(item.getId());

            boolean ready = !blocked && !terminal && category.map(CATEGORY_OPEN::equals).orElse(false);

            boolean inProgress = category.map(CATEGORY_IN_PROGRESS::equals).orElse(false);
            boolean liveAgent = own != null
                    && WorkItemAgentSession.ACTIVITY_WORKING.equals(own.getActivity())
                    && own.getLastHeartbeatAt() != null
                    && own.getLastHeartbeatAt().isAfter(staleBefore);
            boolean stale = inProgress && !terminal && item.getUpdatedAt() != null
                    && item.getUpdatedAt().isBefore(staleBefore) && !liveAgent;

            boolean needsInput = isAsking(own);
            for (String below : descendants.getOrDefault(item.getId(), List.of())) {
                if (needsInput) {
                    break;
                }
                needsInput = isAsking(latestOpen.get(below));
            }
            result.put(item.getId(), new Signals(blocked, List.copyOf(blockers), ready, stale, needsInput, own));
        }
        return result;
    }

    private static boolean isAsking(WorkItemAgentSession session) {
        return session != null && WorkItemAgentSession.ACTIVITY_NEEDS_INPUT.equals(session.getActivity());
    }
}
