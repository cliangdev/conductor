package com.conductor.workflow.model;

import java.util.List;

/**
 * A {@code github.pull_request} event trigger — sibling to {@link ConductorEventTrigger}, not a
 * generalization of it (see the plan's design note on the deferred generic event-bus). {@code
 * actionFilter} restricts which PR actions fire the workflow (empty = unfiltered, any of the four
 * supported actions); {@code labelFilter} restricts by PR label (empty = unfiltered, any label or
 * none).
 *
 * @param eventType    the event key, always {@code "github.pull_request"}
 * @param actionFilter target PR actions to match against, or empty for no filter
 * @param labelFilter  target PR labels to match against, or empty for no filter
 */
public record GitHubPullRequestTrigger(String eventType, List<String> actionFilter, List<String> labelFilter) {

    public GitHubPullRequestTrigger {
        actionFilter = Copies.list(actionFilter);
        labelFilter = Copies.list(labelFilter);
    }
}
