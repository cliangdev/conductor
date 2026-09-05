package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PublishFinding;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Guards the approval gate of a <em>publishing</em> Workflow (COND-23): a Post may not be approved until it
 * is actually publishable — it carries a fire time and a timezone, at least one publish target, and at least
 * one uploaded media file, with the fire time far enough out that every platform will still accept it.
 *
 * <h2>When this runs (the genericity rule)</h2>
 * Neither the status name {@code APPROVED} nor the workflow slug {@code MARKETING} is a trigger here. The
 * validator fires when <b>both</b> of the following are true of the Work Item's own bound {@link Statechart}:
 *
 * <ol>
 *   <li><b>The edge being traversed is a publish gate</b> — the {@code from -> to} transition declares
 *       {@code requiresReview}, or it enters the status the Workflow publishes from
 *       ({@link PublishingWorkflow#isGateEdge}). The first is the definition-driven spelling of "a human
 *       signs this off before it becomes real"; the second is what makes a Workflow with no review gate
 *       still safe, and what catches a Post approved in time but scheduled too late.
 *       <p>Deliberately keyed on edges <em>into</em> the scheduled status, never out of it. MARKETING
 *       declares an ungated {@code SCHEDULED -> APPROVED} ("Unschedule") back-edge; guarding that would
 *       make unscheduling impossible exactly when it matters most — once the fire time is inside the
 *       platform's floor or already past, the human could no longer pull the post back.</li>
 *   <li><b>The workflow declares publishing as a concept</b> — at least one of its declared
 *       {@code asset_types} names a platform the publishing pipeline can target (the
 *       {@link PublishPlatformRegistry}, the same vocabulary {@code post_publish_target.platform} uses). MARKETING
 *       declares {@code facebook_post}/{@code instagram_post}/{@code youtube_video}/{@code tiktok_post} and
 *       opts in; ENGINEERING declares only {@code github_pr} and is untouched, review-gated edges included.
 *       Any authored workflow that declares a platform asset type opts in by saying so in its definition.</li>
 * </ol>
 *
 * <p>The second condition is a heuristic over {@code asset_types} only because the statechart schema has no
 * first-class "this workflow publishes" declaration yet. When it grows one, replace
 * {@link PublishPlatformRegistry#declaresPublishing} with a read of that field; nothing else here changes.
 *
 * <h2>The lead-time floor</h2>
 * Each platform declares how much notice it needs ({@link PublishPlatform#minLead}): Facebook's native
 * {@code scheduled_publish_time} refuses anything under ten minutes out, an app-managed destination needs
 * only the dispatch poller's next tick, a manual one needs nothing but "in the future". The Post's floor is
 * the largest over its selected destinations, and the message names the destination that demands it. With
 * no destination selected yet the historical ten-minute default stands, so an author scheduling before
 * choosing accounts is never told a time is fine and then told it is not.
 *
 * <p>Purely a read-and-throw: it never writes, so a rejection leaves the Work Item exactly as it was and the
 * caller's status change never commits. Every rejection names the specific field that is missing or invalid,
 * and all problems found are reported together so one pass fixes everything.
 */
@Component
public class PostScheduleValidator {

    /**
     * The floor a Post with no destinations yet, or a destination on an unknown platform, is held to. See
     * the class javadoc.
     */
    public static final Duration MINIMUM_LEAD_TIME = PublishPlatform.DEFAULT_MIN_LEAD;

    public static final String NO_FIRE_TIME = "NO_FIRE_TIME";
    public static final String NO_TIMEZONE = "NO_TIMEZONE";
    public static final String UNKNOWN_TIMEZONE = "UNKNOWN_TIMEZONE";
    public static final String FIRE_TIME_TOO_SOON = "FIRE_TIME_TOO_SOON";
    public static final String NO_TARGETS = "NO_TARGETS";
    public static final String NO_MEDIA = "NO_MEDIA";
    public static final String TARGET_MEDIA_MISSING = "TARGET_MEDIA_MISSING";

    private final PublishPlatformRegistry platformRegistry;
    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository postPublishTargetRepository;
    private final PublishTargetMediaResolver mediaResolver;
    private final Clock clock;

    @Autowired
    public PostScheduleValidator(PublishPlatformRegistry platformRegistry,
                                 AssetRepository assetRepository,
                                 PostPublishTargetRepository postPublishTargetRepository,
                                 PublishTargetMediaResolver mediaResolver) {
        this(platformRegistry, assetRepository, postPublishTargetRepository, mediaResolver, Clock.systemUTC());
    }

    PostScheduleValidator(PublishPlatformRegistry platformRegistry,
                          AssetRepository assetRepository,
                          PostPublishTargetRepository postPublishTargetRepository,
                          PublishTargetMediaResolver mediaResolver,
                          Clock clock) {
        this.platformRegistry = platformRegistry;
        this.assetRepository = assetRepository;
        this.postPublishTargetRepository = postPublishTargetRepository;
        this.mediaResolver = mediaResolver;
        this.clock = clock;
    }

    /**
     * Rejects a move onto a publishing workflow's approval gate unless the Work Item is actually publishable.
     * A no-op for every other transition and for every workflow that does not declare publish targets, so the
     * caller can invoke it unconditionally on the transition-validation path.
     *
     * @param workItem    the item being transitioned, at its current (pre-transition) status
     * @param statechart  the item's own resolved, version-pinned statechart
     * @param toStatus    the status being moved to
     * @throws UnprocessableEntityException naming every missing or invalid field, when the gate is not met
     */
    public void validateForTransition(WorkItem workItem, Statechart statechart, String toStatus) {
        if (!appliesTo(workItem, statechart, toStatus)) {
            return;
        }
        List<String> problems = inspect(workItem).stream()
                .filter(PublishFinding::blocks)
                .map(PublishFinding::message)
                .toList();
        if (!problems.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Cannot move " + statechart.noun() + " to " + toStatus + ": "
                            + String.join("; ", problems));
        }
    }

    /** Whether the {@code -> toStatus} move out of the item's current status is one this validator guards. */
    public boolean appliesTo(WorkItem workItem, Statechart statechart, String toStatus) {
        if (workItem == null || statechart == null || toStatus == null) {
            return false;
        }
        return platformRegistry.declaresPublishing(statechart)
                && PublishingWorkflow.isGateEdge(statechart, workItem.getCurrentStatus(), toStatus);
    }

    /**
     * Everything this validator would refuse the Post for, right now, regardless of what status it is in
     * or whether anyone is trying to move it. The list a preflight shows and the list a refused transition
     * throws are the same list.
     */
    public List<PublishFinding> inspect(WorkItem workItem) {
        List<PublishFinding> findings = new ArrayList<>();
        List<PostPublishTarget> targets = postPublishTargetRepository.findAllByWorkItemId(workItem.getId());
        appendScheduleProblems(workItem, targets, findings);
        if (targets.isEmpty()) {
            findings.add(PublishFinding.blocker(NO_TARGETS,
                    "no publish target is selected — pick at least one account to publish to"));
        }
        appendMediaProblems(workItem, targets, findings);
        return findings;
    }

    /**
     * The earliest fire time these destinations would accept from "now": the largest lead over the
     * selection, or the default floor when nothing is selected yet. What a client shows as "as soon as
     * possible".
     */
    public OffsetDateTime earliestFireTime(List<PostPublishTarget> targets) {
        return OffsetDateTime.now(clock).plus(leadTimeFor(targets).lead()).withNano(0).plusSeconds(1);
    }

    /** The floor a selection is held to, and the destination that demands it (null for the default). */
    record LeadTime(Duration lead, PostPublishTarget demandedBy) {}

    LeadTime leadTimeFor(List<PostPublishTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return new LeadTime(MINIMUM_LEAD_TIME, null);
        }
        LeadTime longest = new LeadTime(Duration.ZERO, null);
        for (PostPublishTarget target : targets) {
            PublishPlatform platform = platformRegistry.find(target.getPlatform()).orElse(null);
            Duration lead = platform == null ? MINIMUM_LEAD_TIME : platform.minLead(target.getLane());
            if (lead.compareTo(longest.lead()) > 0) {
                longest = new LeadTime(lead, platform == null ? null : target);
            }
        }
        return longest;
    }

    /**
     * "Has this Post got media?" is per destination now that a destination can choose its own, and the two
     * ways of having none need different answers. Nothing uploaded at all is a Post-level problem, said
     * once. A destination that chose files which were then deleted is its own problem, and saying "upload
     * media" there would be wrong — there is media, it just is not selected here any more.
     */
    private void appendMediaProblems(WorkItem workItem, List<PostPublishTarget> targets,
                                     List<PublishFinding> findings) {
        if (!hasUploadedFileAsset(workItem)) {
            findings.add(PublishFinding.blocker(NO_MEDIA,
                    "no uploaded media file is attached — upload at least one image or video"));
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        Map<String, PublishTargetMediaResolver.EffectiveMedia> byTarget =
                mediaResolver.effectiveMediaByTarget(workItem.getId(), targets);
        for (PostPublishTarget target : targets) {
            PublishTargetMediaResolver.EffectiveMedia media = byTarget.getOrDefault(
                    target.getId(), PublishTargetMediaResolver.EffectiveMedia.NONE);
            if (media.isEmpty()) {
                findings.add(PublishFinding.blocker(TARGET_MEDIA_MISSING,
                        platformLabel(target) + " has no media — the files chosen for it are no longer "
                                + "on this Post; pick media for it or reset it to the Post's",
                        target.getId()));
            }
        }
    }

    private String platformLabel(PostPublishTarget target) {
        String platform = target.getPlatform() == null ? "" : target.getPlatform();
        String account = target.getPlatformAccountLabel();
        return account == null || account.isBlank() ? platform : platform + " (" + account + ")";
    }

    private void appendScheduleProblems(WorkItem workItem, List<PostPublishTarget> targets,
                                        List<PublishFinding> findings) {
        OffsetDateTime fireTime = workItem.getScheduledFor();
        if (fireTime == null) {
            findings.add(PublishFinding.blocker(NO_FIRE_TIME,
                    "no fire time is set — set a scheduled publish time (scheduledFor)"));
        }
        String timezone = workItem.getScheduleTimezone();
        if (timezone == null || timezone.isBlank()) {
            findings.add(PublishFinding.blocker(NO_TIMEZONE,
                    "no schedule timezone is set — set an IANA timezone (scheduleTimezone)"));
        } else if (!isKnownZone(timezone)) {
            findings.add(PublishFinding.blocker(UNKNOWN_TIMEZONE,
                    "the schedule timezone '" + timezone + "' is not a known IANA timezone"));
        }
        if (fireTime == null) {
            return;
        }
        LeadTime lead = leadTimeFor(targets);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (lead.lead().isZero()) {
            if (!fireTime.isAfter(now)) {
                findings.add(PublishFinding.blocker(FIRE_TIME_TOO_SOON,
                        "the fire time " + fireTime + " is not in the future — schedule it later than now"));
            }
            return;
        }
        if (fireTime.isBefore(now.plus(lead.lead()))) {
            long minutes = Math.max(1, lead.lead().toMinutes());
            String unit = minutes == 1 ? " minute" : " minutes";
            String demand = lead.demandedBy() == null ? ""
                    : " (" + platformLabel(lead.demandedBy()) + " needs at least " + minutes + unit + "' notice)";
            findings.add(PublishFinding.blocker(FIRE_TIME_TOO_SOON,
                    "the fire time " + fireTime + " is less than " + minutes + unit
                            + " in the future — schedule it at least " + minutes + unit + " out" + demand,
                    lead.demandedBy() == null ? null : lead.demandedBy().getId()));
        }
    }

    private boolean isKnownZone(String timezone) {
        try {
            ZoneId.of(timezone);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private boolean hasUploadedFileAsset(WorkItem workItem) {
        return assetRepository.findAllByWorkItemId(workItem.getId()).stream()
                .anyMatch(PostScheduleValidator::isUploadedFile);
    }

    private static boolean isUploadedFile(Asset asset) {
        return AssetService.KIND_FILE.equals(asset.getKind())
                && AssetService.UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus());
    }

}
