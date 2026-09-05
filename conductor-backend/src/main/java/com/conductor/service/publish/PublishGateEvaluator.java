package com.conductor.service.publish;

import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.service.MediaTargetValidator;
import com.conductor.service.PostScheduleValidator;
import com.conductor.service.PublishOptionsValidator;
import com.conductor.workflow.lifecycle.Statechart;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The publish gate as one thing: the three validators that decide whether a Post may go out, asked the
 * same question two ways.
 *
 * <p>{@link #enforce} is what a status change runs — it throws the 422 a refused transition has always
 * thrown, in the same order (schedule, then media, then options) with the same words, because each
 * validator still decides for itself whether the edge is one it guards and throws its own message.
 * {@link #evaluate} is what a preflight reads — every finding from every validator, blockers and
 * warnings, regardless of the Post's status or of whether anybody is trying to move it. Both come from the
 * same {@code inspect} methods, so the answer a client is shown beforehand and the answer the transition
 * gives cannot disagree.
 */
@Component
public class PublishGateEvaluator {

    /** What the gate has to say, split the way a client reads it. */
    public record Evaluation(List<PublishFinding> blockers, List<PublishFinding> warnings) {

        public Evaluation {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean ready() {
            return blockers.isEmpty();
        }
    }

    private final PublishingWorkflow publishingWorkflow;
    private final PostScheduleValidator postScheduleValidator;
    private final MediaTargetValidator mediaTargetValidator;
    private final PublishOptionsValidator publishOptionsValidator;

    public PublishGateEvaluator(PublishingWorkflow publishingWorkflow,
                                PostScheduleValidator postScheduleValidator,
                                MediaTargetValidator mediaTargetValidator,
                                PublishOptionsValidator publishOptionsValidator) {
        this.publishingWorkflow = publishingWorkflow;
        this.postScheduleValidator = postScheduleValidator;
        this.mediaTargetValidator = mediaTargetValidator;
        this.publishOptionsValidator = publishOptionsValidator;
    }

    /**
     * Whether the {@code from -> to} move is one the gate guards: the Workflow publishes, and the edge is
     * its review gate or an entry into its scheduled status.
     */
    public boolean appliesTo(Statechart statechart, String fromStatus, String toStatus) {
        return publishingWorkflow.declaresPublishing(statechart)
                && PublishingWorkflow.isGateEdge(statechart, fromStatus, toStatus);
    }

    /**
     * Refuses a guarded transition the Post is not ready for, with the first validator's problems — the
     * schedule and target problems, then the media rules, then the publish options — so one message names
     * everything of one kind and the fix is a single pass. A no-op for an edge the gate does not guard.
     *
     * @throws UnprocessableEntityException naming every problem the first failing validator found
     */
    public void enforce(WorkItem workItem, Statechart statechart, String toStatus) {
        postScheduleValidator.validateForTransition(workItem, statechart, toStatus);
        mediaTargetValidator.validateForTransition(workItem, statechart, toStatus);
        publishOptionsValidator.validateForTransition(workItem, statechart, toStatus);
    }

    /** Every finding from every validator, right now, whatever status the Post is in. */
    public Evaluation evaluate(WorkItem workItem) {
        List<PublishFinding> all = new ArrayList<>(postScheduleValidator.inspect(workItem));
        all.addAll(mediaTargetValidator.inspect(workItem));
        all.addAll(publishOptionsValidator.inspect(workItem));
        return new Evaluation(
                all.stream().filter(PublishFinding::blocks).toList(),
                all.stream().filter(f -> !f.blocks()).toList());
    }
}
