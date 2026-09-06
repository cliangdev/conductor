package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishFinding;
import com.conductor.service.publish.PublishPlatform;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Enforces every per-platform media rule at the approval gate of a <em>publishing</em> Workflow (COND-23),
 * so a format a platform will refuse is caught by a human at review time and never at fire time. A Post that
 * silently fails to publish at 09:00 because Instagram rejected a PNG is the failure mode this exists to
 * prevent; a rule that can only be discovered by the platform saying no is a rule this validator has failed.
 *
 * <h2>When this runs</h2>
 * Exactly the same definition-driven rule {@link PostScheduleValidator} uses, and for the same reasons: the
 * edge being traversed must declare {@code requiresReview}, and the workflow must declare at least one
 * {@code asset_types} entry naming a platform in the {@link PublishPlatformRegistry}. Keying on
 * the <em>edge</em> rather than the status keeps MARKETING's ungated {@code SCHEDULED -> APPROVED}
 * ("Unschedule") back-edge open, so a human can always pull a post back. ENGINEERING declares only
 * {@code github_pr}, so its own review-gated edge is never even queried.
 *
 * <h2>What is checked</h2>
 * Every confirmed file upload on the Work Item is checked against every selected target, because the whole
 * media bundle goes out to every account. Rules are per platform:
 * <ul>
 *   <li><b>Instagram</b> — a feed image must be JPEG (a PNG is an error, not a conversion), with an aspect
 *       ratio between 4:5 and 1.91:1 inclusive. Video is out of scope of the feed-image rule.</li>
 *   <li><b>Facebook</b> — video at most {@value #FACEBOOK_MAX_VIDEO_BYTES} bytes (1.5 GB).</li>
 *   <li><b>TikTok</b> — video no longer than <em>that connection's own</em> cached
 *       {@code maxVideoPostDurationSec}, which is a per-creator cap TikTok reports at connect time and
 *       {@code TikTokConnector} stores in the connection's non-secret config; plus a 4 GB file ceiling.</li>
 *   <li><b>YouTube</b> — a vertical or square video of at most three minutes is a <b>warning</b>: YouTube
 *       will auto-classify it as a Short. That is a surprise worth flagging, not a failure, so it never
 *       blocks.</li>
 * </ul>
 *
 * <h2>Unknown metadata blocks; it never silently passes</h2>
 * A rule whose input is missing is reported as a problem naming the file and the rule it could not check,
 * not skipped. Skipping would convert "we don't know" into "it's fine", and the only place that difference
 * shows up is at fire time — the one place this pipeline promises never to fail. The cost of the strict
 * reading is a re-upload; the cost of the lenient one is a post that never went out. Note this applies only
 * to rules that actually bear on a selected target: an image with unknown dimensions is a problem for an
 * Instagram target and irrelevant to a Facebook one, and the YouTube Shorts check simply produces no warning
 * when it cannot tell, since a warning asserts nothing that could fail.
 *
 * <h2>Warnings</h2>
 * Errors throw and block. Warnings cannot — the transition succeeds — so they are returned in
 * {@link Result} for the caller to relay, and logged at WARN. {@link Result} is safe to ignore, which keeps
 * the call site a one-liner for callers that have no channel to surface advisories on.
 *
 * <p>Purely a read-and-throw: it never writes, so a rejection leaves the Work Item exactly as it was.
 */
@Component
public class MediaTargetValidator {

    private static final Logger log = LoggerFactory.getLogger(MediaTargetValidator.class);

    /** Instagram's narrowest accepted feed aspect, 4:5 (portrait). */
    public static final double INSTAGRAM_MIN_ASPECT = 4.0 / 5.0;
    /** Instagram's widest accepted feed aspect, 1.91:1 (landscape). */
    public static final double INSTAGRAM_MAX_ASPECT = 1.91;
    /** Facebook's video file ceiling: 1.5 GB. */
    public static final long FACEBOOK_MAX_VIDEO_BYTES = 1_610_612_736L;
    /** TikTok's video file ceiling: 4 GB. */
    public static final long TIKTOK_MAX_VIDEO_BYTES = 4L * 1024 * 1024 * 1024;
    /** Longest video YouTube still auto-classifies as a Short, when vertical or square. */
    public static final int YOUTUBE_SHORT_MAX_SECONDS = 180;

    /**
     * Key {@code TikTokConnector} caches the creator's per-account video length cap under, inside the
     * connection's non-secret {@code config_json}. Duplicated rather than imported because the connector's
     * own constant is package-private to its connector package.
     */
    static final String CONFIG_MAX_VIDEO_DURATION_SEC = "maxVideoPostDurationSec";

    /** Floating-point slack so a file sized to hit a ratio exactly (1910x1000 = 1.91) is not rejected. */
    private static final double ASPECT_EPSILON = 0.005;

    private static final String INSTAGRAM_FEED_IMAGE_TYPE = "image/jpeg";

    /** Finding codes. A file's type, size, dimensions or aspect a platform refuses, or cannot be checked. */
    public static final String MEDIA_FORMAT = "MEDIA_FORMAT";
    /** The set as a whole: too many items, a mix a platform refuses, or the wrong count. */
    public static final String MEDIA_COMPOSITION = "MEDIA_COMPOSITION";
    /** Caption or title longer than a platform accepts. */
    public static final String COPY_TOO_LONG = "COPY_TOO_LONG";
    /** An advisory the platform will act on without refusing (a Short, a cropped carousel, a cut title). */
    public static final String MEDIA_ADVISORY = "MEDIA_ADVISORY";
    /** A story destination was given anything other than exactly one media item. */
    public static final String STORY_SINGLE_ITEM = "STORY_SINGLE_ITEM";
    /** A story's media is far from the 9:16 shape the surface is read in — advisory, not a refusal. */
    public static final String STORY_ASPECT = "STORY_ASPECT";
    /** A caption (Post or override) was set on a story, which the platform drops on arrival. */
    public static final String STORY_CAPTION_IGNORED = "STORY_CAPTION_IGNORED";
    /** A reel destination was given anything other than exactly one video, no images. */
    public static final String REEL_SINGLE_VIDEO = "REEL_SINGLE_VIDEO";
    /** A reel's video is far from the 9:16 shape the surface is read in — advisory, not a refusal. */
    public static final String REEL_ASPECT = "REEL_ASPECT";
    /** A Facebook feed post whose one item is a video, which Facebook now publishes as a Reel. */
    public static final String FACEBOOK_VIDEO_IS_REEL = "FACEBOOK_VIDEO_IS_REEL";
    /** A target's chosen format is not one its platform offers at all — defensive; selection already refuses it. */
    public static final String FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED";

    /** Instagram publishes 1 item, or a carousel of 2 to 10. */
    public static final int INSTAGRAM_MAX_CAROUSEL_ITEMS = 10;
    /** TikTok photo posts carry up to 35 images. */
    public static final int TIKTOK_MAX_PHOTOS = 35;
    /** TikTok photo posts accept JPEG and WEBP only — notably not PNG. */
    private static final Set<String> TIKTOK_PHOTO_TYPES = Set.of("image/jpeg", "image/webp");

    /** Caption ceilings, in characters, where the platform enforces one. */
    public static final int INSTAGRAM_MAX_CAPTION_CHARS = 2200;
    public static final int TIKTOK_MAX_CAPTION_CHARS = 2200;
    public static final int TIKTOK_MAX_PHOTO_DESCRIPTION_CHARS = 4000;
    public static final int TIKTOK_MAX_PHOTO_TITLE_CHARS = 90;
    public static final int YOUTUBE_MAX_TITLE_CHARS = 100;
    /** YouTube's description ceiling is 5000 *bytes* of UTF-8, not characters. */
    public static final int YOUTUBE_MAX_DESCRIPTION_BYTES = 5000;

    /** Instagram story image ceiling: 8 MB. */
    public static final long INSTAGRAM_STORY_MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Instagram story video ceiling: 100 MB. */
    public static final long INSTAGRAM_STORY_MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    public static final int INSTAGRAM_STORY_MIN_VIDEO_SECONDS = 3;
    public static final int INSTAGRAM_STORY_MAX_VIDEO_SECONDS = 60;
    /** Facebook story photo ceiling: 10 MB. */
    public static final long FACEBOOK_STORY_MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    public static final int FACEBOOK_STORY_MIN_VIDEO_SECONDS = 3;
    public static final int FACEBOOK_STORY_MAX_VIDEO_SECONDS = 60;

    public static final int FACEBOOK_REEL_MIN_SECONDS = 3;
    public static final int FACEBOOK_REEL_MAX_SECONDS = 90;
    public static final int INSTAGRAM_REEL_MIN_SECONDS = 3;
    public static final int INSTAGRAM_REEL_MAX_SECONDS = 900;
    /** Instagram reel file ceiling: 300 MB. */
    public static final long INSTAGRAM_REEL_MAX_VIDEO_BYTES = 300L * 1024 * 1024;

    /** The band around 9:16 (0.5625) a story is expected to sit in; outside it is a warning, not a refusal. */
    private static final double STORY_MIN_ASPECT = 0.5;
    private static final double STORY_MAX_ASPECT = 0.6;
    /** A wider band for a reel: "outside 9:16 by more than a little" tolerates more than a story does. */
    private static final double REEL_MIN_ASPECT = 0.4;
    private static final double REEL_MAX_ASPECT = 0.8;

    private final PublishPlatformRegistry platformRegistry;
    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository postPublishTargetRepository;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final PublishTargetMediaResolver mediaResolver;

    public MediaTargetValidator(PublishPlatformRegistry platformRegistry,
                                AssetRepository assetRepository,
                                PostPublishTargetRepository postPublishTargetRepository,
                                ConnectionRepository connectionRepository,
                                ObjectMapper objectMapper,
                                PublishTargetMediaResolver mediaResolver) {
        this.platformRegistry = platformRegistry;
        this.assetRepository = assetRepository;
        this.postPublishTargetRepository = postPublishTargetRepository;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.mediaResolver = mediaResolver;
    }

    /** Advisory findings from a validation that did not block. Safe to ignore. */
    public record Result(List<String> warnings) {

        static final Result CLEAN = new Result(List.of());

        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Rejects a move onto a publishing workflow's approval gate when any selected target would refuse the
     * attached media. A no-op for every other transition and every non-publishing workflow, so the caller can
     * invoke it unconditionally on the transition-validation path.
     *
     * @param workItem   the item being transitioned, at its current (pre-transition) status
     * @param statechart the item's own resolved, version-pinned statechart
     * @param toStatus   the status being moved to
     * @return advisory warnings for the caller to surface; never null, never a reason to block
     * @throws UnprocessableEntityException naming every offending target and rule, when any rule is violated
     */
    public Result validateForTransition(WorkItem workItem, Statechart statechart, String toStatus) {
        if (!appliesTo(workItem, statechart, toStatus)) {
            return Result.CLEAN;
        }
        List<PublishFinding> findings = inspect(workItem);
        List<String> problems = findings.stream().filter(PublishFinding::blocks).map(PublishFinding::message).toList();
        if (!problems.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Cannot move " + statechart.noun() + " to " + toStatus + ": " + String.join("; ", problems));
        }
        List<String> warnings = findings.stream().map(PublishFinding::message).toList();
        warnings.forEach(warning -> log.warn("Publish media advisory for work item {}: {}",
                workItem.getId(), warning));
        return warnings.isEmpty() ? Result.CLEAN : new Result(warnings);
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
     * Every media rule any selected destination would refuse the Post over, plus the advisories a human
     * should see, right now and regardless of status. Per target, not per Post: two destinations on the
     * same Post routinely publish different files, so a rule is checked against what its own target will
     * actually send. Problems keep the order a refused transition has always listed them in — each target's
     * file rules, then its composition, then its copy.
     */
    public List<PublishFinding> inspect(WorkItem workItem) {
        List<PostPublishTarget> targets = postPublishTargetRepository.findAllByWorkItemId(workItem.getId());
        if (targets.isEmpty()) {
            // PostScheduleValidator owns "you must pick a target"; there is nothing here to check against.
            return List.of();
        }
        Map<String, PublishTargetMediaResolver.EffectiveMedia> mediaByTarget =
                mediaResolver.effectiveMediaByTarget(workItem.getId(), targets);

        List<PublishFinding> findings = new ArrayList<>();
        for (PostPublishTarget target : targets) {
            List<Asset> media = mediaByTarget
                    .getOrDefault(target.getId(), PublishTargetMediaResolver.EffectiveMedia.NONE).assets();
            if (media.isEmpty()) {
                // PostScheduleValidator owns "this target has no media", and says which kind of nothing
                // it is; checking file rules against an empty list here would add nothing but noise.
                continue;
            }
            PostFormat format = PostFormat.parse(target.getFormat());
            PublishPlatform platform = platformRegistry.find(target.getPlatform()).orElse(null);
            if (platform != null && !platform.supports(format)) {
                // Defensive: target selection already refuses a format the platform does not offer, so this
                // should be unreachable in practice. Refusing here rather than falling through to a format's
                // rules keeps a future selection bug from being checked against the wrong shape.
                findings.add(PublishFinding.blocker(FORMAT_UNSUPPORTED, describe(target) + " does not publish a "
                        + format.wire() + " — this format is not offered here", target.getId()));
                continue;
            }
            switch (format) {
                case STORY -> inspectStory(target, media, workItem, findings);
                case REEL -> inspectReel(target, media, findings);
                default -> inspectFeed(target, media, workItem, findings);
            }
        }
        return findings;
    }

    /** The rules unchanged since before formats existed: per-file format, composition, copy. */
    private void inspectFeed(PostPublishTarget target, List<Asset> media, WorkItem workItem,
                             List<PublishFinding> findings) {
        List<String> formatProblems = new ArrayList<>();
        List<String> compositionProblems = new ArrayList<>();
        List<String> copyProblems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Asset asset : media) {
            inspect(target, asset, formatProblems, warnings);
        }
        inspectComposition(target, media, compositionProblems, warnings);
        inspectCopy(target, workItem, media, copyProblems, warnings);
        formatProblems.forEach(p -> findings.add(PublishFinding.blocker(MEDIA_FORMAT, p, target.getId())));
        compositionProblems.forEach(p -> findings.add(PublishFinding.blocker(MEDIA_COMPOSITION, p, target.getId())));
        copyProblems.forEach(p -> findings.add(PublishFinding.blocker(COPY_TOO_LONG, p, target.getId())));
        warnings.forEach(w -> findings.add(PublishFinding.warning(MEDIA_ADVISORY, w, target.getId())));

        if ("facebook".equals(normalizedPlatform(target)) && media.size() == 1 && isVideo(media.get(0))) {
            findings.add(PublishFinding.warning(FACEBOOK_VIDEO_IS_REEL, describe(target)
                    + " is a single video — Facebook now publishes Page videos as Reels (3–90 seconds, "
                    + "vertical recommended)", target.getId()));
        }
    }

    /**
     * A story: exactly one item, checked against that platform's own story limits, with the caption
     * flagged rather than checked — the platform drops it, so a length rule would be checking text that
     * never arrives.
     */
    private void inspectStory(PostPublishTarget target, List<Asset> media, WorkItem post,
                              List<PublishFinding> findings) {
        String where = describe(target);
        if (media.size() != 1) {
            findings.add(PublishFinding.blocker(STORY_SINGLE_ITEM,
                    where + " has " + media.size() + " files — a story is exactly one item", target.getId()));
        } else {
            Asset asset = media.get(0);
            switch (normalizedPlatform(target)) {
                case "instagram" -> inspectInstagramStoryAsset(target, asset, findings);
                case "facebook" -> inspectFacebookStoryAsset(target, asset, findings);
                default -> { }
            }
            warnIfAspectOutsideRange(target, asset, STORY_MIN_ASPECT, STORY_MAX_ASPECT, STORY_ASPECT, "a story",
                    findings);
        }
        String caption = mediaResolver.effectiveCaption(target, post);
        if (caption != null && !caption.isBlank()) {
            findings.add(PublishFinding.warning(STORY_CAPTION_IGNORED, where
                    + " has a caption, but a story carries no caption — " + platformLabel(target)
                    + " will drop it", target.getId()));
        }
    }

    /** A reel: exactly one video, no images, checked against that platform's own reel limits. */
    private void inspectReel(PostPublishTarget target, List<Asset> media, List<PublishFinding> findings) {
        String where = describe(target);
        long videos = media.stream().filter(MediaTargetValidator::isVideo).count();
        if (media.size() != 1 || videos != 1) {
            findings.add(PublishFinding.blocker(REEL_SINGLE_VIDEO,
                    where + " has " + media.size() + " file(s) — a reel is exactly one video, no images",
                    target.getId()));
            return;
        }
        Asset asset = media.get(0);
        switch (normalizedPlatform(target)) {
            case "facebook" -> {
                checkDuration(target, asset, FACEBOOK_REEL_MIN_SECONDS, FACEBOOK_REEL_MAX_SECONDS,
                        "a Facebook reel", findings);
                checkSize(target, asset, FACEBOOK_MAX_VIDEO_BYTES, "a Facebook reel", "1.5 GB", findings);
            }
            case "instagram" -> {
                checkDuration(target, asset, INSTAGRAM_REEL_MIN_SECONDS, INSTAGRAM_REEL_MAX_SECONDS,
                        "an Instagram reel", findings);
                checkSize(target, asset, INSTAGRAM_REEL_MAX_VIDEO_BYTES, "an Instagram reel", "300 MB", findings);
            }
            default -> { }
        }
        warnIfAspectOutsideRange(target, asset, REEL_MIN_ASPECT, REEL_MAX_ASPECT, REEL_ASPECT, "a reel", findings);
    }

    private void inspectInstagramStoryAsset(PostPublishTarget target, Asset asset, List<PublishFinding> findings) {
        if (isImage(asset)) {
            String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
            if (!INSTAGRAM_FEED_IMAGE_TYPE.equals(contentType)) {
                findings.add(PublishFinding.blocker(MEDIA_FORMAT, describe(target, asset) + " is " + contentType
                        + " — an Instagram story image must be JPEG (image/jpeg); re-export it as a JPEG",
                        target.getId()));
                return;
            }
            checkSize(target, asset, INSTAGRAM_STORY_MAX_IMAGE_BYTES, "an Instagram story image", "8 MB", findings);
        } else if (isVideo(asset)) {
            checkDuration(target, asset, INSTAGRAM_STORY_MIN_VIDEO_SECONDS, INSTAGRAM_STORY_MAX_VIDEO_SECONDS,
                    "an Instagram story video", findings);
            checkSize(target, asset, INSTAGRAM_STORY_MAX_VIDEO_BYTES, "an Instagram story video", "100 MB",
                    findings);
        }
    }

    private void inspectFacebookStoryAsset(PostPublishTarget target, Asset asset, List<PublishFinding> findings) {
        if (isVideo(asset)) {
            checkDuration(target, asset, FACEBOOK_STORY_MIN_VIDEO_SECONDS, FACEBOOK_STORY_MAX_VIDEO_SECONDS,
                    "a Facebook story video", findings);
        } else if (isImage(asset)) {
            checkSize(target, asset, FACEBOOK_STORY_MAX_PHOTO_BYTES, "a Facebook story photo", "10 MB", findings);
        }
    }

    /** A duration outside {@code [minSeconds, maxSeconds]}, or unmeasurable, blocks — the family every other rule here follows. */
    private void checkDuration(PostPublishTarget target, Asset asset, int minSeconds, int maxSeconds, String label,
                               List<PublishFinding> findings) {
        MediaMetadata metadata = MediaMetadata.of(asset);
        if (!metadata.hasDuration()) {
            findings.add(PublishFinding.blocker(MEDIA_FORMAT, describe(target, asset) + " has an unknown"
                    + " duration, so " + label + "'s " + minSeconds + "–" + maxSeconds + " second rule cannot be"
                    + " checked — re-upload the video with its duration", target.getId()));
            return;
        }
        BigDecimal duration = metadata.durationSeconds();
        if (duration.compareTo(BigDecimal.valueOf(minSeconds)) < 0
                || duration.compareTo(BigDecimal.valueOf(maxSeconds)) > 0) {
            findings.add(PublishFinding.blocker(MEDIA_FORMAT, describe(target, asset) + " is "
                    + duration.stripTrailingZeros().toPlainString() + " seconds — " + label + " must be "
                    + minSeconds + " to " + maxSeconds + " seconds", target.getId()));
        }
    }

    /** A file size over {@code maxBytes}, or unmeasurable, blocks. */
    private void checkSize(PostPublishTarget target, Asset asset, long maxBytes, String label, String limitLabel,
                           List<PublishFinding> findings) {
        Long size = asset.getSizeBytes();
        if (size == null) {
            findings.add(PublishFinding.blocker(MEDIA_FORMAT, describe(target, asset)
                    + " has an unknown file size, so " + label + "'s " + limitLabel + " limit cannot be checked",
                    target.getId()));
            return;
        }
        if (size > maxBytes) {
            findings.add(PublishFinding.blocker(MEDIA_FORMAT, describe(target, asset) + " is "
                    + formatSize(size) + " — " + label + " must be at most " + limitLabel, target.getId()));
        }
    }

    /** As {@link #warnCarouselAspects}, but a generic band around 9:16 shared by stories and reels. */
    private void warnIfAspectOutsideRange(PostPublishTarget target, Asset asset, double min, double max,
                                          String code, String noun, List<PublishFinding> findings) {
        MediaMetadata metadata = MediaMetadata.of(asset);
        if (!metadata.hasDimensions()) {
            // Unknown metadata never manufactures a warning either: a warning asserts something that could
            // fail, and "we don't know" is not that assertion.
            return;
        }
        double aspect = metadata.aspectRatio().orElseThrow();
        if (aspect < min - ASPECT_EPSILON || aspect > max + ASPECT_EPSILON) {
            findings.add(PublishFinding.warning(code, describe(target, asset) + " has an aspect ratio of "
                    + formatAspect(aspect) + ":1 (" + asset.getWidth() + "x" + asset.getHeight() + ") — "
                    + noun + " reads best near 9:16 (0.56:1)", target.getId()));
        }
    }

    private void inspect(PostPublishTarget target, Asset asset, List<String> problems, List<String> warnings) {
        String platform = target.getPlatform() == null
                ? "" : target.getPlatform().trim().toLowerCase(Locale.ROOT);
        switch (platform) {
            case "instagram" -> inspectInstagram(target, asset, problems);
            case "facebook" -> inspectFacebook(target, asset, problems);
            case "tiktok" -> inspectTikTok(target, asset, problems);
            case "youtube" -> inspectYouTube(target, asset, warnings);
            default -> { }
        }
    }

    /**
     * What this target's media adds up to as one post, as opposed to whether each file is individually
     * acceptable. Every platform here publishes a different <em>shape</em>: Instagram takes one item or a
     * carousel of two to ten, Facebook one video or any number of photos, TikTok one video or up to 35
     * photos and never a mix, YouTube exactly one video. A set that breaks the shape fails at the platform
     * with an opaque error at fire time, long after the reviewer has gone, so it is refused here.
     */
    private void inspectComposition(PostPublishTarget target, List<Asset> media, List<String> problems,
                                    List<String> warnings) {
        String platform = normalizedPlatform(target);
        long videos = media.stream().filter(MediaTargetValidator::isVideo).count();
        long images = media.stream().filter(MediaTargetValidator::isImage).count();
        String where = describe(target);
        switch (platform) {
            case "instagram" -> {
                if (media.size() > INSTAGRAM_MAX_CAROUSEL_ITEMS) {
                    problems.add(where + " has " + media.size() + " files — an Instagram carousel holds at "
                            + "most " + INSTAGRAM_MAX_CAROUSEL_ITEMS + "; drop some for this destination");
                } else if (media.size() > 1) {
                    warnCarouselAspects(media, where, warnings);
                }
            }
            case "facebook" -> {
                if (videos > 1) {
                    problems.add(where + " has " + videos + " videos — a Facebook post carries one video; "
                            + "publish the rest as their own Posts");
                } else if (videos == 1 && images > 0) {
                    problems.add(where + " mixes a video with " + images + " image(s) — a Facebook post is "
                            + "either one video or a set of photos, not both");
                }
            }
            case "tiktok" -> inspectTikTokComposition(target, media, videos, images, where, problems);
            case "youtube" -> {
                if (images > 0) {
                    problems.add(where + " has " + images + " image(s) — YouTube publishes video only; "
                            + "select the video for this destination");
                } else if (videos > 1) {
                    problems.add(where + " has " + videos + " videos — a YouTube upload is one video; "
                            + "select which one goes here");
                }
            }
            default -> { }
        }
    }

    /**
     * TikTok's two post types, which cannot be combined: a video post (exactly one video) or a photo post
     * (up to 35 images, JPEG or WEBP — PNG is rejected outright, unlike everywhere else in this class where
     * PNG is fine).
     */
    private void inspectTikTokComposition(PostPublishTarget target, List<Asset> media, long videos,
                                          long images, String where, List<String> problems) {
        if (videos > 0 && images > 0) {
            problems.add(where + " mixes video and images — a TikTok post is either one video or a photo "
                    + "post, never both");
            return;
        }
        if (videos > 1) {
            problems.add(where + " has " + videos + " videos — a TikTok post carries one");
            return;
        }
        if (images > TIKTOK_MAX_PHOTOS) {
            problems.add(where + " has " + images + " images — a TikTok photo post holds at most "
                    + TIKTOK_MAX_PHOTOS);
            return;
        }
        for (Asset asset : media) {
            if (!isImage(asset)) {
                continue;
            }
            String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
            if (!TIKTOK_PHOTO_TYPES.contains(contentType)) {
                problems.add(describe(target, asset) + " is " + contentType
                        + " — a TikTok photo post accepts JPEG or WEBP only; re-export it as a JPEG");
            }
        }
    }

    /**
     * Instagram crops every carousel item to the <b>first</b> item's aspect ratio, so a carousel of mixed
     * shapes silently loses parts of the later ones. Advisory rather than blocking: the post publishes and
     * may well be what the author wanted, but they should know before a reviewer approves it.
     */
    private void warnCarouselAspects(List<Asset> media, String where, List<String> warnings) {
        OptionalDouble first = MediaMetadata.of(media.get(0)).aspectRatio();
        if (first.isEmpty()) {
            return;
        }
        double reference = first.getAsDouble();
        boolean mixed = media.stream().skip(1)
                .map(MediaMetadata::of)
                .map(MediaMetadata::aspectRatio)
                .filter(OptionalDouble::isPresent)
                .anyMatch(aspect -> Math.abs(aspect.getAsDouble() - reference) > ASPECT_EPSILON);
        if (mixed) {
            warnings.add(where + " is a carousel of mixed aspect ratios — Instagram crops every item to the "
                    + "first one's shape (" + formatAspect(reference) + ":1)");
        }
    }

    /**
     * The copy that will actually go out here, against the platform's own ceilings. Read through the
     * resolver so an overridden caption is checked and an inherited one is checked once per destination —
     * the same text can be fine for Facebook and too long for Instagram.
     */
    private void inspectCopy(PostPublishTarget target, WorkItem post, List<Asset> media,
                             List<String> problems, List<String> warnings) {
        String caption = mediaResolver.effectiveCaption(target, post);
        String title = post.getTitle();
        String where = describe(target);
        switch (normalizedPlatform(target)) {
            case "instagram" -> refuseIfLonger(caption, INSTAGRAM_MAX_CAPTION_CHARS, where, "caption",
                    problems);
            case "tiktok" -> {
                boolean photoPost = media.stream().noneMatch(MediaTargetValidator::isVideo);
                if (photoPost) {
                    refuseIfLonger(caption, TIKTOK_MAX_PHOTO_DESCRIPTION_CHARS, where, "description",
                            problems);
                    if (title != null && title.length() > TIKTOK_MAX_PHOTO_TITLE_CHARS) {
                        warnings.add(where + " has a " + title.length() + "-character title — TikTok cuts a "
                                + "photo post's title to " + TIKTOK_MAX_PHOTO_TITLE_CHARS);
                    }
                } else {
                    refuseIfLonger(caption, TIKTOK_MAX_CAPTION_CHARS, where, "caption", problems);
                }
            }
            case "youtube" -> {
                if (title != null && title.length() > YOUTUBE_MAX_TITLE_CHARS) {
                    problems.add(where + " has a " + title.length() + "-character title — YouTube allows "
                            + YOUTUBE_MAX_TITLE_CHARS + "; shorten the Post's title");
                }
                int bytes = caption == null ? 0 : caption.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > YOUTUBE_MAX_DESCRIPTION_BYTES) {
                    problems.add(where + " has a " + bytes + "-byte description — YouTube allows "
                            + YOUTUBE_MAX_DESCRIPTION_BYTES + " bytes (emoji and accents cost several each)");
                }
            }
            default -> { }
        }
    }

    private static void refuseIfLonger(String caption, int max, String where, String noun,
                                       List<String> problems) {
        if (caption != null && caption.length() > max) {
            problems.add(where + " has a " + caption.length() + "-character " + noun + " — the platform "
                    + "allows " + max + "; shorten it for this destination");
        }
    }

    private static String normalizedPlatform(PostPublishTarget target) {
        return target.getPlatform() == null ? "" : target.getPlatform().trim().toLowerCase(Locale.ROOT);
    }

    private void inspectInstagram(PostPublishTarget target, Asset asset, List<String> problems) {
        if (!isImage(asset)) {
            return;
        }
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        if (!INSTAGRAM_FEED_IMAGE_TYPE.equals(contentType)) {
            problems.add(describe(target, asset) + " is " + contentType
                    + " — Instagram feed images must be JPEG (image/jpeg); re-export it as a JPEG");
            return;
        }
        MediaMetadata metadata = MediaMetadata.of(asset);
        if (!metadata.hasDimensions()) {
            problems.add(describe(target, asset)
                    + " has unknown dimensions, so Instagram's 4:5 to 1.91:1 aspect ratio rule cannot be"
                    + " checked — re-upload the image so its size can be read");
            return;
        }
        double aspect = metadata.aspectRatio().orElseThrow();
        if (aspect < INSTAGRAM_MIN_ASPECT - ASPECT_EPSILON || aspect > INSTAGRAM_MAX_ASPECT + ASPECT_EPSILON) {
            problems.add(describe(target, asset) + " has an aspect ratio of "
                    + formatAspect(aspect) + ":1 (" + asset.getWidth() + "x" + asset.getHeight()
                    + ") — Instagram feed images must be between 4:5 and 1.91:1");
        }
    }

    private void inspectFacebook(PostPublishTarget target, Asset asset, List<String> problems) {
        if (!isVideo(asset)) {
            return;
        }
        Long sizeBytes = asset.getSizeBytes();
        if (sizeBytes == null) {
            problems.add(describe(target, asset)
                    + " has an unknown file size, so Facebook's 1.5 GB video limit cannot be checked");
            return;
        }
        if (sizeBytes > FACEBOOK_MAX_VIDEO_BYTES) {
            problems.add(describe(target, asset) + " is " + formatGigabytes(sizeBytes)
                    + " — Facebook video must be at most 1.5 GB");
        }
    }

    private void inspectTikTok(PostPublishTarget target, Asset asset, List<String> problems) {
        if (!isVideo(asset)) {
            return;
        }
        Long sizeBytes = asset.getSizeBytes();
        if (sizeBytes == null) {
            problems.add(describe(target, asset)
                    + " has an unknown file size, so TikTok's 4 GB video limit cannot be checked");
        } else if (sizeBytes > TIKTOK_MAX_VIDEO_BYTES) {
            problems.add(describe(target, asset) + " is " + formatGigabytes(sizeBytes)
                    + " — TikTok video must be at most 4 GB");
        }

        // The per-creator duration cap is the one rule with no answer on the MANUAL lane: it is read from a
        // connected account's cached creator info, and a manual target has no account. That is a different
        // fact from the usual "we could not check it" this class refuses to wave through — there is nothing
        // to check against, not a missing reading of something that exists — and blocking on it would make a
        // manual TikTok destination impossible to approve, which is the one thing the lane has to allow.
        // TikTok's own composer enforces the cap on the human at post time regardless.
        if (target.getLane() == PublishLane.MANUAL) {
            return;
        }
        Optional<Integer> maxDuration = cachedMaxVideoDurationSec(target);
        if (maxDuration.isEmpty()) {
            problems.add(describe(target, asset)
                    + " cannot be checked because this TikTok account has no cached maximum video duration"
                    + " — reconnect the account to refresh its creator info");
            return;
        }
        MediaMetadata metadata = MediaMetadata.of(asset);
        if (!metadata.hasDuration()) {
            problems.add(describe(target, asset) + " has an unknown duration, so this TikTok account's "
                    + maxDuration.get() + " second maximum cannot be checked — re-upload the video with its"
                    + " duration");
            return;
        }
        BigDecimal duration = metadata.durationSeconds();
        if (duration.compareTo(BigDecimal.valueOf(maxDuration.get())) > 0) {
            problems.add(describe(target, asset) + " is " + duration.stripTrailingZeros().toPlainString()
                    + " seconds long — this TikTok account accepts at most " + maxDuration.get() + " seconds");
        }
    }

    private void inspectYouTube(PostPublishTarget target, Asset asset, List<String> warnings) {
        if (!isVideo(asset)) {
            return;
        }
        MediaMetadata metadata = MediaMetadata.of(asset);
        if (!metadata.isPortraitOrSquare() || !metadata.hasDuration()) {
            return;
        }
        if (metadata.durationSeconds().compareTo(BigDecimal.valueOf(YOUTUBE_SHORT_MAX_SECONDS)) <= 0) {
            warnings.add(describe(target, asset) + " is "
                    + metadata.durationSeconds().stripTrailingZeros().toPlainString() + " seconds and "
                    + asset.getWidth() + "x" + asset.getHeight()
                    + " — YouTube will auto-classify it as a Short");
        }
    }

    /**
     * That connection's own cached {@code maxVideoPostDurationSec}. Empty when the connection is gone or its
     * config never carried the cap, which is a blocking problem rather than an assumed default: the cap is
     * per creator, so there is no safe number to guess.
     */
    private Optional<Integer> cachedMaxVideoDurationSec(PostPublishTarget target) {
        if (target.getConnectionId() == null) {
            return Optional.empty();
        }
        Optional<Connection> connection = connectionRepository.findById(target.getConnectionId());
        if (connection.isEmpty() || connection.get().getConfigJson() == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(connection.get().getConfigJson())
                    .path(CONFIG_MAX_VIDEO_DURATION_SEC);
            if (!node.isNumber() || node.asInt() <= 0) {
                return Optional.empty();
            }
            return Optional.of(node.asInt());
        } catch (Exception e) {
            log.warn("Unreadable config on connection {}: {}", target.getConnectionId(), e.toString());
            return Optional.empty();
        }
    }

    private String describe(PostPublishTarget target, Asset asset) {
        return platformLabel(target) + ": media '" + mediaLabel(asset) + "'";
    }

    /** The destination alone, for a problem about the set rather than about one file in it. */
    private String describe(PostPublishTarget target) {
        return platformLabel(target);
    }

    private String platformLabel(PostPublishTarget target) {
        String name = platformRegistry.labelOf(target.getPlatform());
        String account = target.getPlatformAccountLabel();
        return account == null || account.isBlank() ? name : name + " (" + account + ")";
    }

    private String mediaLabel(Asset asset) {
        if (asset.getLabel() != null && !asset.getLabel().isBlank()) {
            return asset.getLabel();
        }
        return asset.getId() != null ? asset.getId() : "untitled";
    }

    private static String formatAspect(double aspect) {
        return String.format(Locale.ROOT, "%.2f", aspect);
    }

    private static String formatGigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1024L * 1024 * 1024));
    }

    /** MB below a gigabyte, GB at or above it — whichever unit a human reads a story or reel's limit in. */
    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return formatGigabytes(bytes);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (double) (1024 * 1024));
    }


    private static boolean isImage(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("image/");
    }

    private static boolean isVideo(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("video/");
    }
}
