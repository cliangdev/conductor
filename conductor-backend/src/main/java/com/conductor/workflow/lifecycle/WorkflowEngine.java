package com.conductor.workflow.lifecycle;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The pure decision component of the Workflow Lifecycle bounded context (COND-18). Given a
 * {@link Statechart} it answers "is this move allowed?" and "what moves leave here?" — with no database,
 * no Spring state, and no I/O. Orchestration (persistence, security, notifications, exceptions) lives in
 * the service layer; this stays a stateless, unit-testable decision function, mirroring the existing
 * {@code ConditionEvaluator}/{@code WorkflowValidator} components.
 *
 * <p>Actor- and review-aware filtering (the doer projection, review gates) layers on top of these
 * primitives in the work-item service (COND-18 E2/E3); this E1 core is statechart-level only.
 */
@Component
public class WorkflowEngine {

    /** Whether the {@code from -> to} edge exists in the statechart. */
    public boolean canTransition(Statechart statechart, String fromStatus, String toStatus) {
        return statechart.transition(fromStatus, toStatus).isPresent();
    }

    /** All transitions leaving {@code fromStatus}, in declaration order. */
    public List<StatechartTransition> availableTransitions(Statechart statechart, String fromStatus) {
        return statechart.transitionsFrom(fromStatus);
    }
}
