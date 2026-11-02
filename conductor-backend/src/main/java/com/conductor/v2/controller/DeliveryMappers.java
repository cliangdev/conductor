package com.conductor.v2.controller;

import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.entity.WorkItemCriterion;
import com.conductor.entity.WorkItemHealthUpdate;
import com.conductor.generated.v2.model.ForecastFit;
import com.conductor.generated.v2.model.WorkItemAgentActivity;
import com.conductor.generated.v2.model.WorkItemForecast;
import com.conductor.generated.v2.model.WorkItemHealth;
import com.conductor.generated.v2.model.WorkItemHealthDraft;
import com.conductor.generated.v2.model.WorkItemHealthStatus;
import com.conductor.generated.v2.model.WorkItemHealthUpdateResponse;
import com.conductor.service.ThroughputForecastService;
import com.conductor.service.WorkItemHealthService;
import java.util.Map;
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

    /** The item's health, or null when it has nothing to say (no update, no live draft, no risk). */
    static WorkItemHealth health(WorkItemHealthService.Health h) {
        if (h == null) {
            return null;
        }
        WorkItemHealth out = new WorkItemHealth(h.stale(), h.atRisk())
                .health(h.health() == null ? null : WorkItemHealthStatus.fromValue(h.health()))
                .note(h.note())
                .updatedAt(h.updatedAt());
        if (h.pendingDraft() != null) {
            WorkItemHealthService.PendingDraft d = h.pendingDraft();
            out.setPendingDraft(new WorkItemHealthDraft(d.updateId(), WorkItemHealthStatus.fromValue(d.health()),
                    d.note(), d.createdAt()).draftedByLabel(d.draftedByLabel()));
        }
        return out;
    }

    static WorkItemHealthUpdateResponse healthUpdate(WorkItemHealthUpdate u, Map<String, String> confirmerNames) {
        return new WorkItemHealthUpdateResponse(u.getId(), WorkItemHealthStatus.fromValue(u.getHealth()),
                u.getNote(), u.isConfirmed(), u.getCreatedAt())
                .draftedByLabel(u.getDraftedByLabel())
                .confirmedByName(u.getConfirmedBy() == null ? null : confirmerNames.get(u.getConfirmedBy()))
                .confirmedAt(u.getConfirmedAt());
    }

    static WorkItemForecast forecast(ThroughputForecastService.Forecast f) {
        return new WorkItemForecast(WorkItemForecast.StateEnum.fromValue(f.state().name()), f.remaining(),
                f.samples())
                .mode(f.mode() == null ? null : WorkItemForecast.ModeEnum.fromValue(f.mode().name()))
                .needed(f.needed())
                .weeklyThroughputMedian(f.weeklyThroughputMedian())
                .p50Date(f.p50Date())
                .p85Date(f.p85Date())
                .appetiteEndsOn(f.appetiteEndsOn())
                .appetiteFit(fit(f.appetiteFit()))
                .milestoneFit(fit(f.milestoneFit()));
    }

    private static ForecastFit fit(ThroughputForecastService.Fit fit) {
        return fit == null ? null : ForecastFit.fromValue(fit.name());
    }
}
