package com.conductor.service.signal;

import com.conductor.service.WorkItemService;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED} and, if the merged PR's body says so, closes
 * the linked Conductor issue. Moved out of {@code GitHubConnector} in A8: "does this PR body close a
 * Conductor issue" is Work Item domain knowledge, not GitHub-payload knowledge, so {@link
 * #CLOSES_PATTERN} lives here rather than in the connector -- the connector's job ends at publishing the
 * raw fact that a PR merged, carrying the raw {@code body} along for whoever cares to parse it.
 *
 * <p>{@link FailureMode#PROPAGATE}: this reproduces the pre-A8 behavior where a failure completing the
 * Work Item (anything other than {@link EntityNotFoundException}) escaped {@code
 * GitHubConnector.handleEvent} as a {@code RuntimeException} -- which is what marks the {@code
 * webhook_event} FAILED and gets it retried. {@link SignalDispatchOrder#PULL_REQUEST_MERGE} runs AFTER
 * {@link com.conductor.knowledge.signal.KnowledgeSignalSink} (order {@link
 * SignalDispatchOrder#KNOWLEDGE}), matching today's submitMergedPrKnowledge -> completeFromPullRequest
 * sequence.
 */
@Component
public class PullRequestMergeSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PullRequestMergeSubscriber.class);

    private static final Pattern CLOSES_PATTERN =
            Pattern.compile("closes\\s+conductor/([A-Z]+-\\d+)", Pattern.CASE_INSENSITIVE);

    private final WorkItemService workItemService;

    public PullRequestMergeSubscriber(WorkItemService workItemService) {
        this.workItemService = workItemService;
    }

    @Override
    public String name() {
        return "pull-request-merge";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        // Defense-in-depth: interestedIn already filters to this type before the bus ever calls
        // onSignal, but tests may call onSignal directly and should get the same no-op contract.
        if (!SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signal.type())) {
            return;
        }

        Map<String, Object> payload = signal.payload();
        String prBody = String.valueOf(payload.getOrDefault("body", ""));
        Object htmlUrl = payload.get("htmlUrl");
        String prUrl = htmlUrl != null ? htmlUrl.toString() : "";

        Matcher matcher = CLOSES_PATTERN.matcher(prBody);
        if (!matcher.find()) {
            log.warn("Skipping merged PR {} - body lacks 'closes conductor/ISSUE-KEY'.", prUrl);
            return;
        }
        String displayId = matcher.group(1).toUpperCase();
        String[] parts = displayId.split("-");
        String projectKey = parts[0];
        int sequenceNumber = Integer.parseInt(parts[1]);

        // Aggregate mutation + DONE notifications live in the domain service. The cross-project guard
        // (issue must belong to this connection's project) is enforced there via projectId.
        try {
            workItemService.completeFromPullRequest(signal.projectId(), projectKey, sequenceNumber, prUrl);
            log.info("Issue {}-{} completed from merged PR {}", projectKey, sequenceNumber, prUrl);
        } catch (EntityNotFoundException notFound) {
            // No such issue, or it belongs to another project sharing this installation — skip quietly.
            log.warn("Skipping merged PR {} - {}", prUrl, notFound.getMessage());
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.PULL_REQUEST_MERGE;
    }

    @Override
    public FailureMode failureMode() {
        return FailureMode.PROPAGATE;
    }
}
