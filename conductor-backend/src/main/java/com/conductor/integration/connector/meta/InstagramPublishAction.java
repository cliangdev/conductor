package com.conductor.integration.connector.meta;

import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.meta.MetaConnector.MetaActions;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import com.conductor.integration.connector.meta.MetaConnector.PublishMediaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Instagram Content Publishing two-step, run end to end inside <b>one</b> invocation at fire time:
 * create a media container on {@code /{ig-user-id}/media}, wait for Meta to finish ingesting it, then
 * {@code /{ig-user-id}/media_publish} it.
 *
 * <h2>Why nothing is pre-created</h2>
 * A container expires roughly 24 hours after it is minted, and Meta fetches the media URL <em>during</em>
 * container creation. Creating containers at approval time — the obvious way to make fire time cheap —
 * therefore trades a fast publish for a post that silently fails whenever approval-to-fire exceeds a day
 * or the signed URL lapses first. So the container is created here, from a signed URL minted here.
 *
 * <h2>The publishing limit is checked first</h2>
 * Instagram caps an account at 100 published posts per rolling 24 hours. Creating a container against an
 * exhausted quota produces an object that can never be published and expires unused, so the quota is read
 * <b>before</b> the container — for a Story exactly as for a feed post or a Reel, since Meta counts a
 * published Story against the same rolling budget. Exhaustion is reported as {@link ActionResult#error} —
 * PERMANENT for this attempt, because the retry budget is minutes and the quota window is hours; a human
 * reschedules.
 *
 * <h2>Format</h2>
 * The target's {@code format} arrives lower-case. A {@code story} takes exactly one image or video, a
 * {@code STORIES} container, and no caption — Instagram ignores one, so it is never sent. A {@code reel},
 * and a {@code feed} post whose single item is a video (already {@code REELS} — every feed video is a
 * Reel on Instagram), may carry {@code cover_url} (resolved from {@code cover_asset_id}, one of the
 * Post's own image Assets), {@code share_to_feed}, up to three {@code collaborators} and
 * {@code audio_name}. A single feed image may carry {@code alt_text}. A carousel accepts none of the
 * Reel options — Instagram refuses collaborators on one, so they are dropped with a log line rather than
 * sent and rejected.
 *
 * <h2>Carousels</h2>
 * Two or more items become a carousel: one container per item with {@code is_carousel_item=true}, then a
 * {@code CAROUSEL} parent naming them in order. The caption belongs to the parent — Instagram ignores one
 * set on a child — and the order is the destination's chosen order, which matters because Instagram crops
 * every item to the first one's aspect ratio.
 *
 * <p><b>Known gap: children are not checkpointed.</b> A transient failure partway through creating them
 * re-creates the ones already made on the retry, leaving the first batch to expire unused after ~24 hours.
 * That is wasteful but not incorrect — an unpublished container is inert, and the invocation's idempotency
 * key still guarantees at most one {@code media_publish}. Worth fixing if carousels of videos become common,
 * since those are the slow ones.
 *
 * <h2>Failure classification</h2>
 * Same contract as {@link FacebookPublishAction}: 4xx (except {@code 429}) → {@link ActionResult#error}
 * (permanent, dead-lettered); 5xx and network errors propagate as thrown exceptions (transient, retried).
 */
class InstagramPublishAction {

    private static final Logger log = LoggerFactory.getLogger(InstagramPublishAction.class);

    static final String ACTION_PUBLISH = "publish_instagram_media";
    static final String ACTION_METRICS = "get_instagram_media_metrics";

    /** Like and comment counts for a batch of media — the {@code post_metrics} feed's read; see the Facebook twin. */
    ActionResult metrics(Map<String, Object> input, ConnectionContext ctx) {
        List<String> mediaIds = MetaConnector.MetaActions.stringList(input, "post_ids");
        if (mediaIds.isEmpty()) {
            return ActionResult.error(ACTION_METRICS + " requires 'post_ids'");
        }
        String token = MetaConnector.MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no access token; reconnect the account");
        }
        List<MetaGraphClient.PostMetrics> read;
        try {
            read = graphClient.readMediaMetrics(mediaIds, token);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return MetaConnector.MetaActions.permanentOrRethrow(e, "Instagram could not read media metrics");
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (MetaGraphClient.PostMetrics m : read) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("post_id", m.id());
            row.put("unavailable", m.unavailable());
            if (m.likes() != null) row.put("likes", m.likes());
            if (m.comments() != null) row.put("comments", m.comments());
            rows.add(row);
        }
        return ActionResult.ok(Map.of("metrics", rows));
    }

    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    private static final String MEDIA_TYPE_REELS = "REELS";
    private static final String MEDIA_TYPE_CAROUSEL = "CAROUSEL";
    private static final String MEDIA_TYPE_STORIES = "STORIES";

    private static final String FORMAT_STORY = "story";
    private static final String FORMAT_REEL = "reel";

    /** Instagram publishes a carousel of 2 to 10 items; the approval gate refuses more long before here. */
    static final int MAX_CAROUSEL_ITEMS = 10;

    /** Instagram allows at most three tagged collaborators on a Reel or feed post. */
    static final int MAX_COLLABORATORS = 3;

    /** How long to wait between container status polls. Package-private so tests don't sleep. */
    long pollIntervalMillis = 5_000L;

    /**
     * Poll ceiling. Meta's own guidance is to give a video container up to five minutes; past that the
     * container is treated as stuck rather than left to hold the invocation open to its deadline.
     */
    int maxPollAttempts = 60;

    /** Injectable sleep so the poll loop is testable without real time passing. */
    Sleeper sleeper = Thread::sleep;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final MetaGraphClient graphClient;
    private final PublishMediaResolver mediaResolver;

    InstagramPublishAction(MetaGraphClient graphClient, PublishMediaResolver mediaResolver) {
        this.graphClient = graphClient;
        this.mediaResolver = mediaResolver;
    }

    ActionResult publish(Map<String, Object> input, ConnectionContext ctx) {
        String igUserId = MetaActions.stringConfig(ctx, MetaConnector.CONFIG_IG_ACCOUNT_ID);
        if (igUserId == null) {
            return ActionResult.error("The connected Facebook Page has no linked Instagram Business account");
        }
        String token = MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }

        List<PublishMedia> media;
        String explicitImageUrl = MetaActions.string(input, "image_url");
        String explicitVideoUrl = MetaActions.string(input, "video_url");
        try {
            media = MetaActions.resolveMediaList(mediaResolver, input, explicitImageUrl, explicitVideoUrl)
                    .stream()
                    .filter(item -> item.url() != null && !item.url().isBlank())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not resolve Instagram media for work item {}: {}",
                    MetaActions.string(input, "work_item_id"), e.getMessage());
            return ActionResult.error("Could not resolve media for this post: " + e.getMessage());
        }
        if (media.isEmpty()) {
            return ActionResult.error("publish_instagram_media needs an image or video — Instagram has no text-only post");
        }
        if (media.size() > MAX_CAROUSEL_ITEMS) {
            return ActionResult.error("An Instagram carousel holds at most " + MAX_CAROUSEL_ITEMS
                    + " items; this post selected " + media.size());
        }

        String workItemId = MetaActions.string(input, "work_item_id");
        String format = MetaActions.string(input, "format");
        boolean storyRequested = FORMAT_STORY.equalsIgnoreCase(format);
        boolean reelRequested = FORMAT_REEL.equalsIgnoreCase(format);

        if (storyRequested && media.size() != 1) {
            return ActionResult.error("An Instagram Story needs exactly one image or video; this destination "
                    + "selected " + media.size() + " items");
        }
        if (reelRequested && (media.size() != 1 || !media.get(0).isVideo())) {
            return ActionResult.error("An Instagram Reel needs exactly one video; this destination selected "
                    + media.size() + " item(s)");
        }

        // Instagram ignores a Story's caption outright, so it is never sent.
        String caption = storyRequested ? null : MetaActions.string(input, "caption");

        try {
            MetaGraphClient.PublishingLimit limit = graphClient.readContentPublishingLimit(igUserId, token);
            if (limit.exhausted()) {
                return ActionResult.error("Instagram publishing limit reached: this account has used "
                        + limit.quotaUsage() + " of its " + limit.quotaTotal()
                        + "-posts-per-24-hours cap, so no container can be created. Reschedule this post for "
                        + "after the rolling 24-hour window clears.");
            }

            Map<String, Object> output = new LinkedHashMap<>();
            String containerId;
            if (storyRequested) {
                PublishMedia only = media.get(0);
                containerId = graphClient.createMediaContainer(igUserId, token, storyContainerParams(only));
                if (only.isVideo()) {
                    ActionResult stuck = awaitContainer(containerId, token);
                    if (stuck != null) {
                        return stuck;
                    }
                }
                output.put("is_story", true);
            } else if (media.size() == 1) {
                PublishMedia only = media.get(0);
                String mediaType = mediaType(MetaActions.string(input, "media_type"), only);
                Map<String, String> extras = Map.of();
                if (MEDIA_TYPE_REELS.equals(mediaType)) {
                    ReelOptions options = resolveReelOptions(input, workItemId);
                    if (options.error() != null) {
                        return options.error();
                    }
                    extras = options.params();
                }
                String altText = MEDIA_TYPE_IMAGE.equals(mediaType) ? MetaActions.string(input, "alt_text") : null;
                containerId = graphClient.createMediaContainer(igUserId, token,
                        containerParams(mediaType, only, caption, extras, altText));
                if (!MEDIA_TYPE_IMAGE.equals(mediaType)) {
                    ActionResult stuck = awaitContainer(containerId, token);
                    if (stuck != null) {
                        return stuck;
                    }
                }
            } else {
                List<String> collaborators = MetaActions.stringList(input, "collaborators");
                if (!collaborators.isEmpty()) {
                    // Instagram refuses collaborators on a carousel outright; dropping them here is what
                    // lets the rest of the post go out rather than failing on a parameter it never asked for.
                    log.info("Instagram carousels do not accept collaborators; ignoring {} name(s) for work "
                            + "item {}", collaborators.size(), workItemId);
                }
                List<String> childIds = new ArrayList<>();
                for (PublishMedia item : media) {
                    // Children carry no caption — the caption belongs to the carousel, and Instagram
                    // ignores one set on a child.
                    String childId = graphClient.createMediaContainer(igUserId, token,
                            carouselItemParams(item));
                    childIds.add(childId);
                    if (item.isVideo()) {
                        ActionResult stuck = awaitContainer(childId, token);
                        if (stuck != null) {
                            return stuck;
                        }
                    }
                }
                output.put("children_creation_ids", childIds);
                containerId = graphClient.createMediaContainer(igUserId, token,
                        carouselParams(childIds, caption));
                // The parent is a container like any other and is not instantly publishable, even though
                // every child already finished: Instagram assembles the carousel itself.
                ActionResult stuck = awaitContainer(containerId, token);
                if (stuck != null) {
                    return stuck;
                }
            }

            String mediaId = graphClient.publishMediaContainer(igUserId, token, containerId);

            output.put("media_id", mediaId);
            output.put("creation_id", containerId);
            String permalink = permalinkFor(mediaId, token);
            if (permalink != null) {
                output.put("permalink", permalink);
            }
            return ActionResult.ok(output);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Instagram rejected the post");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Interrupted mid-poll: the container exists but its outcome here is unknown, so this is
            // thrown rather than returned — the caller must not treat it as a settled permanent failure.
            throw new IllegalStateException("Interrupted while waiting for the Instagram container to finish", e);
        }
    }

    /**
     * Polls the container until Meta finishes ingesting it. Returns null once it is publishable, or a
     * permanent {@link ActionResult#error} when Meta reports the container failed or it never finishes —
     * an unfinished container cannot be published, and this container is not reusable by a retry.
     */
    private ActionResult awaitContainer(String containerId, String token) throws InterruptedException {
        for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
            MetaGraphClient.ContainerStatus status = graphClient.readContainerStatus(containerId, token);
            if (status.finished()) {
                return null;
            }
            if (status.failed()) {
                return ActionResult.error("Instagram could not process the media (container " + containerId
                        + " reported " + status.statusCode()
                        + (status.errorMessage() != null ? ": " + status.errorMessage() : "") + ")");
            }
            sleeper.sleep(pollIntervalMillis);
        }
        return ActionResult.error("Instagram container " + containerId + " did not finish processing after "
                + maxPollAttempts + " status checks; the media was not published");
    }

    /**
     * The permalink is a best-effort read-back: the media is already live at this point, so failing to
     * read its URL must not turn a successful publish into a retried one.
     */
    private String permalinkFor(String mediaId, String token) {
        try {
            String permalink = graphClient.readMedia(mediaId, token).permalink();
            return permalink != null && !permalink.isBlank() ? permalink : null;
        } catch (RuntimeException e) {
            log.debug("Could not read permalink for Instagram media {}: {}", mediaId, e.getMessage());
            return null;
        }
    }

    /**
     * One carousel child. {@code is_carousel_item=true} is what makes Instagram hold the container for a
     * parent instead of treating it as a post of its own; a video child is {@code VIDEO} and never
     * {@code REELS}, because a Reel cannot be an item in a carousel.
     */
    private static Map<String, String> carouselItemParams(PublishMedia media) {
        Map<String, String> params = new LinkedHashMap<>();
        if (media.isVideo()) {
            params.put("video_url", media.url());
            params.put("media_type", MEDIA_TYPE_VIDEO);
        } else {
            params.put("image_url", media.url());
        }
        params.put("is_carousel_item", "true");
        return params;
    }

    /** The carousel itself: the children in order, and the caption the whole post carries. */
    private static Map<String, String> carouselParams(List<String> childIds, String caption) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("media_type", MEDIA_TYPE_CAROUSEL);
        params.put("children", String.join(",", childIds));
        if (caption != null && !caption.isBlank()) {
            params.put("caption", caption);
        }
        return params;
    }

    /** A Story container: {@code STORIES} plus the one asset's URL. No caption — Instagram ignores one. */
    private static Map<String, String> storyContainerParams(PublishMedia media) {
        Map<String, String> params = new LinkedHashMap<>();
        if (media.isVideo()) {
            params.put("video_url", media.url());
        } else {
            params.put("image_url", media.url());
        }
        params.put("media_type", MEDIA_TYPE_STORIES);
        return params;
    }

    private static Map<String, String> containerParams(String mediaType, PublishMedia media, String caption,
                                                        Map<String, String> extras, String altText) {
        Map<String, String> params = new LinkedHashMap<>();
        if (MEDIA_TYPE_IMAGE.equals(mediaType)) {
            params.put("image_url", media.url());
            if (altText != null && !altText.isBlank()) {
                params.put("alt_text", altText);
            }
        } else {
            params.put("video_url", media.url());
            params.put("media_type", mediaType);
        }
        if (caption != null && !caption.isBlank()) {
            params.put("caption", caption);
        }
        if (extras != null) {
            params.putAll(extras);
        }
        return params;
    }

    /** What {@link #resolveReelOptions} answers with: the params to add, or the error to return instead. */
    private record ReelOptions(Map<String, String> params, ActionResult error) {
        static ReelOptions ok(Map<String, String> params) {
            return new ReelOptions(params, null);
        }

        static ReelOptions fail(ActionResult error) {
            return new ReelOptions(Map.of(), error);
        }
    }

    /**
     * The Reel-only option params: {@code share_to_feed} passed through as given, up to three
     * {@code collaborators} JSON-encoded (Graph's array shape for a form-encoded request), and
     * {@code audio_name}. {@code cover_asset_id} is resolved to a signed URL the same way every other
     * piece of media is — an id naming no image Asset on this Work Item is an error naming it, not a
     * silently coverless Reel.
     */
    private ReelOptions resolveReelOptions(Map<String, Object> input, String workItemId) {
        Map<String, String> params = new LinkedHashMap<>();
        Object shareToFeed = input.get("share_to_feed");
        if (shareToFeed != null) {
            params.put("share_to_feed", String.valueOf(shareToFeed));
        }
        List<String> collaborators = MetaActions.stringList(input, "collaborators");
        if (collaborators.size() > MAX_COLLABORATORS) {
            return ReelOptions.fail(ActionResult.error("Instagram allows at most " + MAX_COLLABORATORS
                    + " collaborators; this post named " + collaborators.size()));
        }
        if (!collaborators.isEmpty()) {
            params.put("collaborators", toJsonArray(collaborators));
        }
        String audioName = MetaActions.string(input, "audio_name");
        if (audioName != null) {
            params.put("audio_name", audioName);
        }
        String coverAssetId = MetaActions.string(input, "cover_asset_id");
        if (coverAssetId != null) {
            List<PublishMedia> cover = mediaResolver.resolve(workItemId, List.of(coverAssetId));
            if (cover.isEmpty() || cover.get(0).url() == null || cover.get(0).url().isBlank()) {
                return ReelOptions.fail(ActionResult.error("Instagram cover_asset_id '" + coverAssetId
                        + "' does not name an image asset on this Work Item"));
            }
            params.put("cover_url", cover.get(0).url());
        }
        return ReelOptions.ok(params);
    }

    private static String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * The caller's declared type wins when it names one; otherwise it is inferred from the resolved
     * media, with video defaulting to {@code REELS} — Instagram publishes every feed video as a Reel.
     */
    private static String mediaType(String declared, PublishMedia media) {
        if (declared != null && !declared.isBlank()) {
            String normalized = declared.trim().toUpperCase(Locale.ROOT);
            if (MEDIA_TYPE_IMAGE.equals(normalized) || MEDIA_TYPE_VIDEO.equals(normalized)
                    || MEDIA_TYPE_REELS.equals(normalized)) {
                return normalized;
            }
        }
        return media.isVideo() ? MEDIA_TYPE_REELS : MEDIA_TYPE_IMAGE;
    }
}
