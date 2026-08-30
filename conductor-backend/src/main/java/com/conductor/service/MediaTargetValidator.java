package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces every per-platform media rule at the approval gate of a <em>publishing</em> Workflow (COND-23),
 * so a format a platform will refuse is caught by a human at review time and never at fire time. A Post that
 * silently fails to publish at 09:00 because Instagram rejected a PNG is the failure mode this exists to
 * prevent; a rule that can only be discovered by the platform saying no is a rule this validator has failed.
 *
 * <h2>When this runs</h2>
 * Exactly the same definition-driven rule {@link PostScheduleValidator} uses, and for the same reasons: the
 * edge being traversed must declare {@code requiresReview}, and the workflow must declare at least one
 * {@code asset_types} entry naming a platform in {@link PostScheduleValidator#PUBLISH_PLATFORMS}. Keying on
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

    private static final Map<String, String> PLATFORM_LABELS = Map.of(
            "facebook", "Facebook",
            "instagram", "Instagram",
            "youtube", "YouTube",
            "tiktok", "TikTok");

    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository postPublishTargetRepository;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;

    public MediaTargetValidator(AssetRepository assetRepository,
                                PostPublishTargetRepository postPublishTargetRepository,
                                ConnectionRepository connectionRepository,
                                ObjectMapper objectMapper) {
        this.assetRepository = assetRepository;
        this.postPublishTargetRepository = postPublishTargetRepository;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
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
        List<PostPublishTarget> targets = postPublishTargetRepository.findAllByWorkItemId(workItem.getId());
        if (targets.isEmpty()) {
            // PostScheduleValidator owns "you must pick a target"; there is nothing here to check against.
            return Result.CLEAN;
        }
        List<Asset> media = assetRepository.findAllByWorkItemId(workItem.getId()).stream()
                .filter(MediaTargetValidator::isUploadedFile)
                .toList();
        if (media.isEmpty()) {
            // PostScheduleValidator owns "you must upload media".
            return Result.CLEAN;
        }

        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (PostPublishTarget target : targets) {
            for (Asset asset : media) {
                inspect(target, asset, problems, warnings);
            }
        }
        if (!problems.isEmpty()) {
            throw new UnprocessableEntityException(
                    "Cannot move " + statechart.noun() + " to " + toStatus + ": " + String.join("; ", problems));
        }
        warnings.forEach(warning -> log.warn("Publish media advisory for work item {}: {}",
                workItem.getId(), warning));
        return warnings.isEmpty() ? Result.CLEAN : new Result(warnings);
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

    private boolean declaresPublishTargets(Statechart statechart) {
        return statechart.assetTypes().stream().anyMatch(MediaTargetValidator::namesPublishPlatform);
    }

    private static boolean namesPublishPlatform(String assetType) {
        if (assetType == null) {
            return false;
        }
        String normalized = assetType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf('_');
        String head = separator < 0 ? normalized : normalized.substring(0, separator);
        return PostScheduleValidator.PUBLISH_PLATFORMS.contains(head);
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

    private String platformLabel(PostPublishTarget target) {
        String platform = target.getPlatform() == null
                ? "" : target.getPlatform().trim().toLowerCase(Locale.ROOT);
        String name = PLATFORM_LABELS.getOrDefault(platform, target.getPlatform());
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

    private static boolean isUploadedFile(Asset asset) {
        return AssetService.KIND_FILE.equals(asset.getKind())
                && AssetService.UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus());
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
