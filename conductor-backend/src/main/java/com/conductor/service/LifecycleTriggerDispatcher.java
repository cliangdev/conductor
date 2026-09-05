package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
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
     * What a system-triggered cascade did to a Work Item.
     *
     * @param trigger       the trigger that ran
     * @param fromStatus    the status the item was in when the cascade started
     * @param toStatus      the status it is in now — how far the cascade got
     * @param blocked       whether a hop was refused by the publish gate; the status is wherever the
     *                      previous hop left it
     * @param blockedReason the gate's own words when blocked, else null
     */
    public record AutoTransition(String trigger, String fromStatus, String toStatus, boolean blocked,
                                 String blockedReason) {
        public boolean applied() {
            return fromStatus != null && !fromStatus.equals(toStatus);
        }
    }

    /**
     * Runs the {@code review_approved} cascade for a Work Item whose gate an APPROVED review has just
     * satisfied, and reports what happened so the reviewer can be told. Unlike the status-changed path
     * this is called directly rather than through a signal: the outcome has to travel back up to the
     * review request, and an approval that could not schedule its Post is something the approver must see.
     *
     * <p>Empty when called from inside another cascade (the nested hop is the outer loop's to take).
     */
    public Optional<AutoTransition> onReviewApproved(String projectId, String workItemId) {
        if (Boolean.TRUE.equals(IN_CASCADE.get())) {
            return Optional.empty();
        }
        IN_CASCADE.set(Boolean.TRUE);
        try {
            return Optional.ofNullable(cascade(projectId, workItemId, WorkItemWorkflowService.TRIGGER_REVIEW_APPROVED));
        } finally {
            IN_CASCADE.remove();
        }
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
            cascade(signal.projectId(), workItemId, WorkItemWorkflowService.TRIGGER_STATUS_CHANGED);
        } finally {
            IN_CASCADE.remove();
        }
    }

    /**
     * One cascade: hop along consecutive edges declaring {@code trigger} until none matches, a status
     * repeats, the hop cap is hit, or the publish gate refuses a hop. Every hop persists, gets the same
     * side effects a human status change gets (revoking a native hand-off on the way out of the scheduled
     * status, re-stamping and handing off on the way in), and re-publishes the status-changed event so
     * notifications and YAML automations fire for it.
     *
     * @return where the item ended up, or null when it could not be loaded
     */
    private AutoTransition cascade(String projectId, String workItemId, String trigger) {
        // Fresh load inside this transaction — never operate on the detached entity carried in the event
        // (accessing its lazy associations later would throw; see #240 §4).
        WorkItem workItem = workItemRepository.findById(workItemId).orElse(null);
        if (workItem == null) {
            return null;
        }
        String startStatus = workItem.getCurrentStatus();
        Set<String> visited = new HashSet<>();
        visited.add(startStatus);

        for (int hops = 0; hops < MAX_CASCADE_HOPS; hops++) {
            String fromStatus = workItem.getCurrentStatus();
            Optional<StatechartTransition> applied;
            try {
                applied = workItemWorkflowService.applySystemTransition(projectId, workItem, trigger);
            } catch (UnprocessableEntityException refused) {
                // The gate said no. The status was never set, so the item stays exactly where the previous
                // hop left it; the reason travels back to whoever started the cascade.
                log.warn("Lifecycle cascade ({}) for Work Item {} stopped at {}: {}",
                        trigger, workItemId, fromStatus, refused.getMessage());
                return new AutoTransition(trigger, startStatus, fromStatus, true, refused.getMessage());
            }
            if (applied.isEmpty()) {
                return new AutoTransition(trigger, startStatus, fromStatus, false, null);
            }
            String toStatus = workItem.getCurrentStatus();
            // Cycle guard BEFORE any side effect: if this hop revisits a status, undo the in-memory advance
            // (the Work Item is managed in the triggering transaction, so an un-reverted mutation would still
            // flush at commit) and stop — a cyclic edge must not persist or publish a hop.
            if (!visited.add(toStatus)) {
                workItem.setCurrentStatus(fromStatus);
                log.warn("Lifecycle cascade for Work Item {} halted: revisited status {} (statechart cycle)",
                        workItemId, toStatus);
                return new AutoTransition(trigger, startStatus, fromStatus, false, null);
            }
            // Leaving the scheduled status revokes a native hand-off FIRST, exactly as a human move does. A
            // failed revocation undoes the in-memory advance so the flush cannot strand a live scheduled post.
            try {
                workItemService.applyScheduledExit(projectId, workItem, fromStatus);
            } catch (RuntimeException revokeFailed) {
                workItem.setCurrentStatus(fromStatus);
                throw revokeFailed;
            }
            workItemRepository.save(workItem);
            workItemService.applyScheduledEntry(projectId, workItem);
            // Re-publish per hop so notifications + YAML automations fire for it. The re-entrancy guard makes
            // the nested lifecycle evaluation a no-op, so this loop stays the sole advancer.
            workItemService.publishStatusChanged(projectId, workItem, fromStatus, toStatus, null);
        }
        log.warn("Lifecycle cascade for Work Item {} halted at the hop cap ({})", workItemId, MAX_CASCADE_HOPS);
        return new AutoTransition(trigger, startStatus, workItem.getCurrentStatus(), false, null);
    }
}
