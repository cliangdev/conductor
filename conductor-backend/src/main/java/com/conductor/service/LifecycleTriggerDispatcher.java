package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.repository.WorkItemRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.lifecycle.StatechartTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Routes a {@link SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} signal into the generalized lifecycle
 * trigger mechanism (#240 §3): a Workflow transition declaring {@code trigger: status_changed} auto-advances a
 * Work Item when its status changes. This lifts the old limitation where only {@code pr_merged} (a GitHub
 * webhook, handled directly by {@code WorkItemService.completeFromPullRequest}) could drive a system transition.
 *
 * <p>Called from {@code LifecycleSignalSubscriber.onSignal}, one of several {@code SignalSubscriber} beans
 * fanned out to by {@code InProcessSignalBus.publish}, so notifications, YAML automations, and lifecycle
 * auto-transitions all react to the same signal.
 *
 * <h3>Loop safety</h3>
 * A {@code status_changed} transition itself changes status, which re-fires the event — an unbounded recursion
 * risk on any cyclic statechart. Three defenses, all here:
 * <ol>
 *   <li><b>Internal cascade loop</b> — one event advances the Work Item hop-by-hop in a {@code for} loop,
 *       rather than relying on re-entrant events to chain hops.</li>
 *   <li><b>Visited-status set + hard cap</b> — a repeated status (checked <em>before</em> the hop is
 *       persisted, so a cyclic edge is a net no-op), or exceeding {@link #MAX_CASCADE_HOPS}, stops the
 *       cascade deterministically.</li>
 *   <li><b>ThreadLocal re-entrancy guard</b> — each hop re-publishes the event (so notifications + YAML
 *       automations fire per hop), which re-enters this dispatcher; the guard makes that nested call a no-op,
 *       leaving the outer loop the sole advancer.</li>
 * </ol>
 * Idempotency falls out for free: a re-delivered event re-loads the Work Item at its current status, and if it
 * already advanced no transition's {@code from} matches, so it is a clean no-op.
 *
 * <p><b>Transactions:</b> this is <em>not</em> {@code @Transactional} — it is only ever invoked (via {@code
 * LifecycleSignalSubscriber}) from inside {@code InProcessSignalBus.publish}, itself called during a status
 * change already inside the triggering request's transaction ({@code WorkItemService.patchWorkItem}/{@code
 * completeFromPullRequest}), whose cascade ops it joins. Deliberately so: were it {@code
 * @Transactional(REQUIRED)}, a cascade exception would be caught by its own interceptor and mark the
 * <em>shared</em> transaction rollback-only, silently failing the user's status change at commit. Without a
 * boundary here, {@code InProcessSignalBus}'s SWALLOW handling genuinely isolates a lifecycle-trigger failure
 * from the triggering request.
 */
@Service
public class LifecycleTriggerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LifecycleTriggerDispatcher.class);

    /** Backstop on a single cascade; the visited-status set is the real cycle terminator. */
    private static final int MAX_CASCADE_HOPS = 25;

    private static final ThreadLocal<Boolean> IN_CASCADE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final WorkItemRepository workItemRepository;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final WorkItemService workItemService;

    public LifecycleTriggerDispatcher(WorkItemRepository workItemRepository,
                                      WorkItemWorkflowService workItemWorkflowService,
                                      WorkItemService workItemService) {
        this.workItemRepository = workItemRepository;
        this.workItemWorkflowService = workItemWorkflowService;
        this.workItemService = workItemService;
    }

    /**
     * The leading type check is defense-in-depth: {@code LifecycleSignalSubscriber.interestedIn} already
     * filters to {@link SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} before {@code onSignal} ever calls
     * this method, but several unit tests call it directly and rely on the same no-op contract.
     */
    public void onConductorEvent(Signal signal) {
        if (!SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signal.type())) {
            return;
        }
        // Re-entrancy guard: a nested event fired by our own cascade hop is a no-op — the outer loop owns it.
        if (Boolean.TRUE.equals(IN_CASCADE.get())) {
            return;
        }
        String workItemId = signal.flatAttributes().get("workItemId");
        if (workItemId == null || workItemId.isBlank()) {
            return;
        }

        IN_CASCADE.set(Boolean.TRUE);
        try {
            cascade(signal.projectId(), workItemId);
        } finally {
            IN_CASCADE.remove();
        }
    }

    private void cascade(String projectId, String workItemId) {
        // Fresh load inside this transaction — never operate on the detached entity carried in the event
        // (accessing its lazy associations later would throw; see #240 §4).
        WorkItem workItem = workItemRepository.findById(workItemId).orElse(null);
        if (workItem == null) {
            return;
        }
        Set<String> visited = new HashSet<>();
        visited.add(workItem.getCurrentStatus());

        for (int hops = 0; hops < MAX_CASCADE_HOPS; hops++) {
            String fromStatus = workItem.getCurrentStatus();
            Optional<StatechartTransition> applied = workItemWorkflowService.applySystemTransition(
                    projectId, workItem, WorkItemWorkflowService.TRIGGER_STATUS_CHANGED);
            if (applied.isEmpty()) {
                return;
            }
            String toStatus = workItem.getCurrentStatus();
            // Cycle guard BEFORE any side effect: if this hop revisits a status, undo the in-memory advance
            // (the Work Item is managed in the triggering transaction, so an un-reverted mutation would still
            // flush at commit) and stop — a cyclic status_changed edge must not persist or publish a hop.
            if (!visited.add(toStatus)) {
                workItem.setCurrentStatus(fromStatus);
                log.warn("Lifecycle cascade for Work Item {} halted: revisited status {} (statechart cycle)",
                        workItemId, toStatus);
                return;
            }
            workItemRepository.save(workItem);
            // Re-publish per hop so notifications + YAML automations fire for it. The re-entrancy guard makes
            // the nested lifecycle evaluation a no-op, so this loop stays the sole advancer.
            workItemService.publishStatusChanged(projectId, workItem, fromStatus, toStatus, null);
        }
        log.warn("Lifecycle cascade for Work Item {} halted at the hop cap ({})", workItemId, MAX_CASCADE_HOPS);
    }
}
