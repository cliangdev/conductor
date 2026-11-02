package com.conductor.v2.controller;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.entity.WorkItemCriterion;
import com.conductor.generated.v2.model.WorkItemAgentActivity;
import com.conductor.generated.v2.model.WorkItemAgentSessionResponse;
import com.conductor.generated.v2.model.WorkItemBlocker;
import com.conductor.generated.v2.model.WorkItemCriterionResponse;
import com.conductor.generated.v2.model.WorkItemSignals;
import com.conductor.service.WorkItemSignalService.Signals;

/**
 * Entity/record to v2 DTO mapping for the delivery-tracking resources (signals, agent activity, criteria,
 * sessions), shared by the Work Item, tree and sub-resource controllers so each renders them identically.
 */
final class DeliveryMappers {

    private DeliveryMappers() {
    }

    static String displayId(WorkItem item) {
        return item.getProject().getKey() + "-" + item.getSequenceNumber();
    }

    static WorkItemSignals signals(Signals s) {
        return new WorkItemSignals(s.blocked(),
                s.blockedBy().stream().map(b -> new WorkItemBlocker(b.id(), b.displayId(), b.title())).toList(),
                s.ready(), s.stale(), s.needsInput());
    }

    /** The latest open session as the item's {@code agentActivity}; null when none. */
    static WorkItemAgentActivity agentActivity(WorkItemAgentSession session) {
        if (session == null) {
            return null;
        }
        return new WorkItemAgentActivity(session.getAgentLabel(),
                WorkItemAgentActivity.ModeEnum.fromValue(session.getMode()),
                WorkItemAgentActivity.ActivityEnum.fromValue(session.getActivity()),
                session.getLastHeartbeatAt())
                .note(session.getNote());
    }

    static WorkItemCriterionResponse criterion(WorkItemCriterion c) {
        return new WorkItemCriterionResponse(c.getId(), c.getPosition(), c.getText(),
                WorkItemCriterionResponse.KindEnum.fromValue(c.getKind()), c.isMet())
                .externalRef(c.getExternalRef())
                .evidence(c.getEvidence())
                .metAt(c.getMetAt())
                .metByLabel(c.getMetByLabel());
    }

    static WorkItemAgentSessionResponse session(WorkItemAgentSession s) {
        return new WorkItemAgentSessionResponse(s.getSessionKey(), s.getAgentLabel(),
                WorkItemAgentSessionResponse.ModeEnum.fromValue(s.getMode()),
                WorkItemAgentSessionResponse.ActivityEnum.fromValue(s.getActivity()),
                s.getStartedAt(), s.getLastHeartbeatAt())
                .note(s.getNote())
                .stepRunId(s.getStepRunId())
                .endedAt(s.getEndedAt());
    }
}
