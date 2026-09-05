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
import com.conductor.service.publish.PublishFinding;
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
        }
        return findings;
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


    private static boolean isImage(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("image/");
    }

    private static boolean isVideo(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        return contentType != null && contentType.startsWith("video/");
    }
}
