package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
 *   <li><b>The edge being traversed is the workflow's approval gate</b> — the {@code from -> to} transition
 *       declares {@code requiresReview}. That is the definition-driven spelling of "a human signs this off
 *       before it becomes real", whatever the workflow chose to name the status.
 *       <p>Deliberately keyed on the <em>edge</em>, not on "any edge into that status". MARKETING also
 *       declares an ungated {@code SCHEDULED -> APPROVED} ("Unschedule") back-edge; keying on the status
 *       would make unscheduling impossible exactly when it matters most — once the fire time is inside the
 *       ten-minute floor or already past, the human could no longer pull the post back.</li>
 *   <li><b>The workflow declares publishing as a concept</b> — at least one of its declared
 *       {@code asset_types} names a platform the publishing pipeline can target (see
 *       {@link #PUBLISH_PLATFORMS}, the same vocabulary {@code post_publish_target.platform} uses). MARKETING
 *       declares {@code facebook_post}/{@code instagram_post}/{@code youtube_video}/{@code tiktok_post} and
 *       opts in; ENGINEERING declares only {@code github_pr} and is untouched, review-gated edges included.
 *       Any authored workflow that declares a platform asset type opts in by saying so in its definition.</li>
 * </ol>
 *
 * <p>The second condition is a heuristic over {@code asset_types} only because the statechart schema has no
 * first-class "this workflow publishes" declaration yet. When it grows one, replace
 * {@link #declaresPublishTargets} with a read of that field; nothing else here changes.
 *
 * <h2>The ten-minute floor</h2>
 * Facebook's native {@code scheduled_publish_time} refuses anything under ten minutes out. The floor is
 * applied uniformly to every platform rather than per-platform so the rule a human learns once holds
 * everywhere — a Post that is approvable is approvable for all of its targets.
 *
 * <p>Purely a read-and-throw: it never writes, so a rejection leaves the Work Item exactly as it was and the
 * caller's status change never commits. Every rejection names the specific field that is missing or invalid,
 * and all problems found are reported together so one pass fixes everything.
 */
@Component
public class PostScheduleValidator {

    /**
     * Facebook's native scheduling minimum, applied uniformly to every platform. See the class javadoc.
     */
    public static final Duration MINIMUM_LEAD_TIME = Duration.ofMinutes(10);

    /**
     * Platforms the publishing pipeline can target — the {@code post_publish_target.platform} vocabulary. A
     * workflow declaring an {@code asset_types} entry named for one of these (e.g. {@code facebook_post})
     * declares publishing as a concept.
     */
    static final Set<String> PUBLISH_PLATFORMS = Set.of("facebook", "instagram", "youtube", "tiktok");

    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository postPublishTargetRepository;
    private final Clock clock;

    @Autowired
    public PostScheduleValidator(AssetRepository assetRepository,
                                 PostPublishTargetRepository postPublishTargetRepository) {
        this(assetRepository, postPublishTargetRepository, Clock.systemUTC());
    }

    PostScheduleValidator(AssetRepository assetRepository,
                          PostPublishTargetRepository postPublishTargetRepository,
                          Clock clock) {
        this.assetRepository = assetRepository;
        this.postPublishTargetRepository = postPublishTargetRepository;
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
        List<String> problems = collectProblems(workItem);
        if (!problems.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Cannot move " + statechart.noun() + " to " + toStatus + ": "
                            + String.join("; ", problems));
        }
    }

    private boolean appliesTo(WorkItem workItem, Statechart statechart, String toStatus) {
        if (workItem == null || statechart == null || toStatus == null) {
            return false;
        }
        if (!declaresPublishTargets(statechart)) {
            return false;
        }
        Optional<StatechartTransition> transition =
                statechart.transition(workItem.getCurrentStatus(), toStatus);
        return transition.isPresent() && transition.get().requiresReview();
    }

    /**
     * Whether this workflow treats publish targets as a concept, read off its own declared {@code asset_types}:
     * an entry named for a platform in {@link #PUBLISH_PLATFORMS} (e.g. {@code instagram_post}, or a bare
     * {@code youtube}) means the workflow's items go out to platforms.
     */
    private boolean declaresPublishTargets(Statechart statechart) {
        return statechart.assetTypes().stream().anyMatch(this::namesPublishPlatform);
    }

    private boolean namesPublishPlatform(String assetType) {
        if (assetType == null) {
            return false;
        }
        String normalized = assetType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('_');
        String head = separator < 0 ? normalized : normalized.substring(0, separator);
        return PUBLISH_PLATFORMS.contains(head);
    }

    private List<String> collectProblems(WorkItem workItem) {
        List<String> problems = new ArrayList<>();
        appendScheduleProblems(workItem, problems);
        if (postPublishTargetRepository.findAllByWorkItemId(workItem.getId()).isEmpty()) {
            problems.add("no publish target is selected — pick at least one account to publish to");
        }
        if (!hasUploadedFileAsset(workItem)) {
            problems.add("no uploaded media file is attached — upload at least one image or video");
        }
        return problems;
    }

    private void appendScheduleProblems(WorkItem workItem, List<String> problems) {
        OffsetDateTime fireTime = workItem.getScheduledFor();
        if (fireTime == null) {
            problems.add("no fire time is set — set a scheduled publish time (scheduledFor)");
        }
        String timezone = workItem.getScheduleTimezone();
        if (timezone == null || timezone.isBlank()) {
            problems.add("no schedule timezone is set — set an IANA timezone (scheduleTimezone)");
        } else if (!isKnownZone(timezone)) {
            problems.add("the schedule timezone '" + timezone + "' is not a known IANA timezone");
        }
        if (fireTime != null) {
            OffsetDateTime earliest = OffsetDateTime.now(clock).plus(MINIMUM_LEAD_TIME);
            if (fireTime.isBefore(earliest)) {
                problems.add("the fire time " + fireTime
                        + " is less than " + MINIMUM_LEAD_TIME.toMinutes()
                        + " minutes in the future — schedule it at least "
                        + MINIMUM_LEAD_TIME.toMinutes() + " minutes out");
            }
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
