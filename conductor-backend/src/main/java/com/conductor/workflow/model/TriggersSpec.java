package com.conductor.workflow.model;

import java.util.List;
import java.util.Map;

/**
 * The workflow's {@code on:} block. Multiple trigger kinds can be combined in one workflow. An
 * unrecognized key under {@code on:} (workflows in the wild — and several existing tests — use
 * placeholder trigger keys like {@code push:} or {@code issue_updated:}) is preserved in {@link
 * #raw()} but otherwise ignored: {@code WorkflowValidator} has never rejected unknown trigger keys,
 * and this parser doesn't start now.
 *
 * @param schedule            the {@code schedule:} trigger, or null if absent
 * @param webhook             the {@code webhook:} trigger, or null if absent
 * @param events              {@code conductor.*} event triggers declared (0 or 1 today)
 * @param hasWorkflowDispatch whether {@code workflow_dispatch:} is present
 * @param raw                 the full {@code on:} block, verbatim
 */
public record TriggersSpec(ScheduleTrigger schedule, WebhookTrigger webhook,
                           List<ConductorEventTrigger> events, boolean hasWorkflowDispatch,
                           Map<String, Object> raw) {

    public TriggersSpec {
        events = Copies.list(events);
        raw = Copies.map(raw);
    }
}
