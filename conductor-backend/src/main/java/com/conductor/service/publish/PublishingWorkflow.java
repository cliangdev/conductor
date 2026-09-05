package com.conductor.service.publish;

import com.conductor.entity.WorkItem;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What a publishing Workflow's statechart says about publishing, read in one place.
 *
 * <p>Three questions used to be answered by three different heuristics scattered across the pipeline:
 * "does this Workflow publish at all?" (an {@code asset_types} scan copied into four classes), "which
 * status does a Post wait in for its fire time?" (the literal {@code "SCHEDULED"} in both pollers, the
 * outcome service, the bundle guard and the Work Item service), and "which edges are the publish gate?"
 * (only the {@code requiresReview} edge, which left a Workflow with no review gate publishing with no
 * checks at all). They are answered here, from the definition.
 *
 * <h2>The scheduled status</h2>
 * A definition names it with {@code publishes_from}. A snapshot pinned before that field existed does not,
 * and cannot be edited — Work Items pin their {@code workflow_version} — so a chart with no marker falls
 * back to a status literally called {@code SCHEDULED} when it has one. That fallback is what keeps every
 * existing MARKETING Post dispatching after this deploy.
 *
 * <h2>The gate</h2>
 * A transition is a publish gate when it is review-gated <em>or</em> it enters the scheduled status. The
 * second half is the new part, and it closes two holes: a Post approved with fifteen minutes to spare and
 * scheduled five minutes later used to enter the scheduled status unvalidated (and then sit {@code
 * PENDING} forever, because the native hand-off refused a fire time inside its window); and a Workflow
 * that chose to have no review gate used to get no validation whatsoever. Edges <em>out of</em> the
 * scheduled status — MARKETING's "Unschedule" — are deliberately not gates, so a human can always pull a
 * post back.
 */
@Component
public class PublishingWorkflow {

    /** The status a chart with no {@code publishes_from} marker is assumed to dispatch from, if it has one. */
    public static final String LEGACY_SCHEDULED_STATUS = "SCHEDULED";

    private static final String DEFAULT_WORKFLOW = "ENGINEERING";

    private final PublishPlatformRegistry platformRegistry;
    private final WorkflowDefinitionResolver resolver;

    public PublishingWorkflow(PublishPlatformRegistry platformRegistry, WorkflowDefinitionResolver resolver) {
        this.platformRegistry = platformRegistry;
        this.resolver = resolver;
    }

    /** Whether this Workflow treats publishing as a concept — see {@link PublishPlatformRegistry#declaresPublishing}. */
    public boolean declaresPublishing(Statechart chart) {
        return platformRegistry.declaresPublishing(chart);
    }

    /**
     * The status a Post waits in for its fire time: the chart's {@code publishes_from}, or the legacy
     * {@code SCHEDULED} when the chart predates the marker and has such a status. Empty for a chart that
     * has neither, which no publishing chart can be — the definition validator refuses to publish one.
     */
    public static Optional<String> scheduledStatus(Statechart chart) {
        if (chart == null) {
            return Optional.empty();
        }
        Optional<String> declared = chart.publishesFrom();
        if (declared.isPresent()) {
            return declared;
        }
        return chart.hasStatus(LEGACY_SCHEDULED_STATUS) ? Optional.of(LEGACY_SCHEDULED_STATUS) : Optional.empty();
    }

    /** Whether {@code status} is the chart's scheduled status. */
    public static boolean isScheduledStatus(Statechart chart, String status) {
        return status != null && scheduledStatus(chart).filter(status::equals).isPresent();
    }

    /**
     * Whether the {@code from -> to} edge is one the publish validators guard: the review gate, or any edge
     * into the scheduled status. Purely structural — whether the chart publishes at all is
     * {@link #declaresPublishing}'s question.
     */
    public static boolean isGateEdge(Statechart chart, String from, String to) {
        if (chart == null || from == null || to == null) {
            return false;
        }
        Optional<StatechartTransition> transition = chart.transition(from, to);
        if (transition.isEmpty()) {
            return false;
        }
        return transition.get().requiresReview() || isScheduledStatus(chart, to);
    }

    /**
     * The status a Post reaches when every target published: the terminal status the scheduled status
     * transitions to. Publishing is the one way out of the pipeline that ends the item's life, so
     * "terminal" is what identifies it — no status name is assumed.
     */
    public static Optional<String> publishedStatus(Statechart chart) {
        return scheduledStatus(chart).flatMap(scheduled -> chart.transitionsFrom(scheduled).stream()
                .map(StatechartTransition::to)
                .filter(chart::isTerminal)
                .findFirst());
    }

    /**
     * The status a Post reaches when a target failed: the non-terminal status the scheduled status
     * transitions to that is not one of the statuses <em>before</em> scheduling (the "unschedule" edge
     * back to Approved on MARKETING, or back to Draft on a gate-less chart). What is left is the chart's own
     * "publish failed" landing — {@code FAILED} on MARKETING, whatever a different Workflow calls it.
     */
    public static Optional<String> failedStatus(Statechart chart) {
        Set<String> before = statusesBeforeScheduling(chart);
        return scheduledStatus(chart).flatMap(scheduled -> chart.transitionsFrom(scheduled).stream()
                .map(StatechartTransition::to)
                .filter(to -> !chart.isTerminal(to))
                .filter(to -> !before.contains(to))
                .findFirst());
    }

    /**
     * Every status reachable from the initial status without entering the scheduled one: where a Post is
     * authored, reviewed and approved. The complement of {@link #isScheduledOrLater}.
     */
    public static Set<String> statusesBeforeScheduling(Statechart chart) {
        Set<String> before = new HashSet<>();
        if (chart == null) {
            return before;
        }
        String scheduled = scheduledStatus(chart).orElse(null);
        Deque<String> frontier = new ArrayDeque<>();
        chart.initialStatus().ifPresent(initial -> {
            before.add(initial.id());
            frontier.add(initial.id());
        });
        while (!frontier.isEmpty()) {
            for (StatechartTransition transition : chart.transitionsFrom(frontier.poll())) {
                String next = transition.to();
                if (next.equals(scheduled)) {
                    continue;
                }
                if (before.add(next)) {
                    frontier.add(next);
                }
            }
        }
        return before;
    }

    /**
     * Whether {@code status} is the scheduled status or anything reachable from it without going back
     * before scheduling — the region where a Post's bundle is bound to what a platform has been handed.
     */
    public static boolean isScheduledOrLater(Statechart chart, String status) {
        if (chart == null || status == null) {
            return false;
        }
        String scheduled = scheduledStatus(chart).orElse(null);
        if (scheduled == null) {
            return false;
        }
        Set<String> before = statusesBeforeScheduling(chart);
        Set<String> seen = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        seen.add(scheduled);
        frontier.add(scheduled);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            if (current.equals(status)) {
                return true;
            }
            for (StatechartTransition transition : chart.transitionsFrom(current)) {
                String next = transition.to();
                if (before.contains(next)) {
                    continue;
                }
                if (seen.add(next)) {
                    frontier.add(next);
                }
            }
        }
        return false;
    }

    /**
     * The scheduled status of the Workflow this Post is bound to, honouring its pinned version. Falls back
     * to {@link #LEGACY_SCHEDULED_STATUS} when the definition cannot be resolved at all, so a poller keeps
     * dispatching rather than silently stranding every row over a lookup failure.
     */
    public String scheduledStatusOf(WorkItem post) {
        if (post == null) {
            return LEGACY_SCHEDULED_STATUS;
        }
        String projectId = post.getProject() == null ? null : post.getProject().getId();
        String slug = post.getWorkflow() != null ? post.getWorkflow() : DEFAULT_WORKFLOW;
        Optional<Statechart> chart = resolver == null || projectId == null
                ? Optional.empty()
                : resolver.resolve(projectId, slug, post.getWorkflowVersion());
        return chart.map(PublishingWorkflow::scheduledStatus)
                .orElse(Optional.of(LEGACY_SCHEDULED_STATUS))
                .orElse(LEGACY_SCHEDULED_STATUS);
    }

    /** Whether this Post is sitting in its Workflow's scheduled status — the only status a poller may fire from. */
    public boolean isInScheduledStatus(WorkItem post) {
        return post != null && post.getCurrentStatus() != null
                && post.getCurrentStatus().equals(scheduledStatusOf(post));
    }
}
