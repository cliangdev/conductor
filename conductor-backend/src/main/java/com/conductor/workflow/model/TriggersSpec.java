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
 * @param pullRequestEvents   {@code github.pull_request} event triggers declared (0 or 1 today) —
 *                            a distinct trigger kind/shape from {@code events}, not folded into it
 * @param hasWorkflowDispatch whether {@code workflow_dispatch:} is present
 * @param raw                 the full {@code on:} block, verbatim
 */
public record TriggersSpec(ScheduleTrigger schedule, WebhookTrigger webhook,
                           List<ConductorEventTrigger> events,
                           List<GitHubPullRequestTrigger> pullRequestEvents,
                           boolean hasWorkflowDispatch,
                           Map<String, Object> raw) {

    public TriggersSpec {
        events = Copies.list(events);
        pullRequestEvents = Copies.list(pullRequestEvents);
        raw = Copies.map(raw);
    }

    /**
     * Whether a human (or an external caller like the {@code dispatch_workflow} MCP tool) can fire
     * this workflow via {@code POST .../dispatch}. True whenever {@code workflow_dispatch:} is
     * declared, unless the workflow opts out with {@code on.workflow_dispatch.manual: false} — for a
     * system-managed workflow whose event payload is built programmatically (e.g. the Knowledge
     * Center's librarian, which expects a dispatcher-supplied {@code agentSlug}/{@code sourceIds} that
     * a manual click can never provide). Defaults to true so existing workflows (including ones with
     * {@code workflow_dispatch: {}} and declared {@code inputs:}, like knowledge-bootstrap) are
     * unaffected.
     */
    public boolean allowsManualDispatch() {
        if (!hasWorkflowDispatch) return false;
        Object workflowDispatch = raw.get("workflow_dispatch");
        if (workflowDispatch instanceof Map<?, ?> wd && Boolean.FALSE.equals(wd.get("manual"))) {
            return false;
        }
        return true;
    }
}
