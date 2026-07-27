package com.conductor.disposition;

import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A {@link SignalSubscriber} at {@link SignalDispatchOrder#DISPOSITION_POLICY} (last) that consults
 * project-scoped {@link DispositionPolicy} rows for the incoming signal's type.
 *
 * <p><b>This subscriber gates ONLY ITSELF.</b> The structural subscribers earlier in the dispatch order
 * -- {@code NotificationSignalSink}, {@code WorkflowAutomationSignalSubscriber}, {@code
 * LifecycleSignalSubscriber}, {@code KnowledgeSignalSink}, {@code PullRequestMergeSubscriber} -- already
 * ran by the time this one sees the signal, and this class has no way to un-run them, nor should it try
 * to. A {@code BLOCKED} match here vetoes only whatever THIS subscriber would otherwise do with the
 * signal -- never the whole {@code publish()} call. Wiring a {@code BLOCKED} policy to short-circuit the
 * entire bus would let one bad row in one project's {@code disposition_policy} table silently disable,
 * say, status-change workflow automation platform-wide, with no code path a reader could grep for to
 * explain why. That is a foot-gun, not a feature -- so it is structurally impossible here: this class
 * has no reference to the bus or to any other subscriber, only to the policy rows themselves.
 *
 * <p>An empty {@code disposition_policy} table (the default for every existing and new project) is a
 * complete no-op: {@link DispositionPolicyCache#matching} returns nothing to match, so this method
 * returns immediately every time. This cannot regress any existing behavior by construction.
 *
 * <p><b>Current scope:</b> no action-taking code exists yet for the non-{@code BLOCKED} dispositions
 * (KNOWLEDGE/WORK_ITEM/NOTIFY/REFERENCE) -- this class today only establishes the routing-rule read
 * path and the {@code BLOCKED} veto contract described above. A future phase that actually acts on a
 * matched KNOWLEDGE/WORK_ITEM/NOTIFY/REFERENCE disposition belongs here, once there's a concrete action
 * to gate; until then those dispositions are recorded intent with no side effect.
 */
@Component
public class DispositionPolicySubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(DispositionPolicySubscriber.class);

    private final DispositionPolicyCache cache;

    public DispositionPolicySubscriber(DispositionPolicyCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "disposition-policy";
    }

    /** Every signal type is potentially relevant -- policy rows glob against an open-ended set of
     *  signal types, so narrowing this would mean guessing which ones a project might route. The cache
     *  lookup inside {@link #onSignal} is the real filter, and it's a no-op for any project with no rows. */
    @Override
    public boolean interestedIn(String signalType) {
        return true;
    }

    @Override
    public void onSignal(Signal signal) {
        List<DispositionPolicy> matches = cache.matching(signal.projectId(), signal.type());
        if (matches.isEmpty()) {
            return;
        }
        boolean blocked = matches.stream().anyMatch(p -> p.getDisposition() == Disposition.BLOCKED);
        if (blocked) {
            log.debug("Disposition policy BLOCKED signal '{}' for project {} -- vetoing this subscriber's "
                    + "own handling only", signal.type(), signal.projectId());
            return;
        }
        // See "Current scope" above -- no action exists yet for a non-BLOCKED match.
    }

    @Override
    public int order() {
        return SignalDispatchOrder.DISPOSITION_POLICY;
    }

    @Override
    public FailureMode failureMode() {
        return FailureMode.SWALLOW;
    }
}
