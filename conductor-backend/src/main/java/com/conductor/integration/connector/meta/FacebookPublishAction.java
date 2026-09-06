package com.conductor.integration.connector.meta;

import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.meta.MetaConnector.MetaActions;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import com.conductor.integration.connector.meta.MetaConnector.PublishMediaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Facebook Page actions: publish/schedule a feed post, a Reel or a Story; take one back down; and
 * read whether a scheduled feed post or Reel has gone live.
 *
 * <h2>Media is resolved here, not handed in</h2>
 * The publishing scheduler's payload deliberately carries only the copy plus {@code work_item_id} /
 * {@code target_id} handles. Turning those into a URL Meta can fetch is this class's job, and the
 * signed read URL is minted <b>inside this invocation</b> ({@link PublishMediaResolver#resolve}) rather
 * than at approval time: Meta fetches the bytes during the publish call, and a URL minted when a human
 * approved the post days earlier would have expired by the fire time.
 *
 * <h2>Format</h2>
 * The target's {@link com.conductor.service.publish.PostFormat} arrives lower-case under {@code format}.
 * A {@code story} publishes immediately through the Page's Story edges and carries no caption — Meta
 * ignores one, so it is never sent and a supplied caption only gets a log line. A {@code reel}, and a
 * {@code feed} post whose selected media is a single video, both go out through the Page Reels API
 * ({@code /video_reels}): Meta's Reels guide says this is now the <b>only</b> way to publish a Page
 * video, so the old {@code /videos} chunked-upload edge is never used for a Page video any more,
 * feed or Reel. A Reel wants 3-90 seconds of video, 9:16 recommended.
 *
 * <h2>Scheduling</h2>
 * A future fire time goes out as {@code published=false} plus a unix {@code scheduled_publish_time} for
 * a feed post, or {@code video_state=SCHEDULED} plus the same timestamp for a Reel — both are the only
 * shape Meta honours; the timestamp alone is silently ignored and the post goes out immediately. A fire
 * time already in the past publishes now instead, because Meta rejects a {@code scheduled_publish_time}
 * in the past and the post is already late. A Story cannot be scheduled by Meta at all, so one is always
 * published immediately and any {@code scheduled_publish_time} on the input is ignored.
 *
 * <h2>Failure classification</h2>
 * Per {@link com.conductor.integration.ActionConnector}: a 4xx is a rejection of this exact request and
 * comes back as {@link ActionResult#error} (PERMANENT — dead-lettered, never retried, because retrying a
 * request Meta already refused burns attempts and can duplicate a partially-processed side effect),
 * while a 5xx or a network error is left to propagate as a thrown exception (TRANSIENT — retried). The
 * one 4xx that is genuinely transient, {@code 429 Too Many Requests}, is rethrown rather than returned.
 */
class FacebookPublishAction {

    private static final Logger log = LoggerFactory.getLogger(FacebookPublishAction.class);

    static final String ACTION_PUBLISH = "publish_facebook_post";
    static final String ACTION_DELETE = "delete_facebook_post";
    static final String ACTION_GET = "get_facebook_post";
    static final String ACTION_METRICS = "get_facebook_post_metrics";

    private static final String FORMAT_REEL = "reel";
    private static final String FORMAT_STORY = "story";

    private final MetaGraphClient graphClient;
    private final PublishMediaResolver mediaResolver;

    FacebookPublishAction(MetaGraphClient graphClient, PublishMediaResolver mediaResolver) {
        this.graphClient = graphClient;
        this.mediaResolver = mediaResolver;
    }

    ActionResult publish(Map<String, Object> input, ConnectionContext ctx) {
        String pageId = MetaActions.stringConfig(ctx, MetaConnector.CONFIG_PAGE_ID);
        if (pageId == null) {
            return ActionResult.error("This Meta connection names no Facebook Page to publish to");
        }
        String token = MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }

        Long scheduledPublishTime;
        try {
            scheduledPublishTime = futureScheduleSeconds(MetaActions.string(input, "scheduled_publish_time"));
        } catch (DateTimeParseException e) {
            return ActionResult.error("scheduled_publish_time is not a valid ISO-8601 instant: "
                    + MetaActions.string(input, "scheduled_publish_time"));
        }

        String message = MetaActions.string(input, "message");
        String link = MetaActions.string(input, "link");
        String title = MetaActions.string(input, "title");
        String format = MetaActions.string(input, "format");

        List<PublishMedia> media;
        String explicitVideoUrl = MetaActions.string(input, "video_url");
        String explicitImageUrl = MetaActions.string(input, "image_url");
        try {
            media = MetaActions.resolveMediaList(mediaResolver, input, explicitImageUrl, explicitVideoUrl)
                    .stream()
                    .filter(item -> item.url() != null && !item.url().isBlank())
                    .toList();
        } catch (RuntimeException e) {
            // Media resolution is local (asset lookup + signing); a failure here is this post's own
            // problem and will not fix itself on a retry.
            log.warn("Could not resolve Facebook media for work item {}: {}",
                    MetaActions.string(input, "work_item_id"), e.getMessage());
            return ActionResult.error("Could not resolve media for this post: " + e.getMessage());
        }

        if (FORMAT_STORY.equalsIgnoreCase(format)) {
            return publishStory(pageId, token, media, message, scheduledPublishTime);
        }

        boolean singleVideoFeed = media.size() == 1 && media.get(0).isVideo();
        if (FORMAT_REEL.equalsIgnoreCase(format) || singleVideoFeed) {
            if (media.size() != 1 || !media.get(0).isVideo()) {
                return ActionResult.error("A Facebook Reel needs exactly one video, 3-90 seconds (9:16 "
                        + "recommended); this destination selected " + media.size() + " item(s)");
            }
            try {
                MetaGraphClient.PublishedPost published = graphClient.publishReel(pageId, token,
                        media.get(0).url(), message, title, scheduledPublishTime);
                return reelSuccess(published, token, scheduledPublishTime);
            } catch (HttpClientErrorException e) {
                return MetaActions.permanentOrRethrow(e, "Facebook rejected the reel");
            }
        }

        boolean hasText = (message != null && !message.isBlank()) || (link != null && !link.isBlank());
        if (media.isEmpty() && !hasText) {
            return ActionResult.error("publish_facebook_post needs a message, a link, or media on the Work Item");
        }

        try {
            MetaGraphClient.PublishedPost published = dispatch(pageId, token, media, message, link,
                    title, scheduledPublishTime);
            return success(published, token, pageId, scheduledPublishTime);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Facebook rejected the post");
        }
    }

    ActionResult delete(Map<String, Object> input, ConnectionContext ctx) {
        String postId = MetaActions.string(input, "post_id");
        if (postId == null) {
            return ActionResult.error("delete_facebook_post requires 'post_id'");
        }
        String token = MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }

        try {
            // A single generic DELETE /{id} takes back a feed post, a Reel video or a Story alike — Meta
            // does not need to be told which shape the id names.
            graphClient.deletePost(postId, token);
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone — the caller's goal (this post must not go live) is satisfied, and reporting a
            // failure would strand a Work Item that is in fact revoked.
            log.info("Facebook post {} was already deleted", postId);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Facebook refused to delete post " + postId);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", postId);
        output.put("deleted", true);
        return ActionResult.ok(output);
    }

    /**
     * Engagement counts for a batch of Page posts — the {@code post_metrics} feed's read. Answers one
     * {@code metrics} entry per requested id, in order, with {@code unavailable} for an id Graph no longer
     * resolves — including a Reel id, which does not answer the Page-post insights fields this reads —
     * so one deleted or unreadable post never hides the numbers of the rest.
     */
    ActionResult metrics(Map<String, Object> input, ConnectionContext ctx) {
        List<String> postIds = MetaConnector.MetaActions.stringList(input, "post_ids");
        if (postIds.isEmpty()) {
            return ActionResult.error(ACTION_METRICS + " requires 'post_ids'");
        }
        String token = MetaConnector.MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }
        List<MetaGraphClient.PostMetrics> read;
        try {
            read = graphClient.readPostMetrics(postIds, token);
        } catch (HttpClientErrorException e) {
            return MetaConnector.MetaActions.permanentOrRethrow(e, "Facebook could not read post metrics");
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (MetaGraphClient.PostMetrics m : read) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("post_id", m.id());
            row.put("unavailable", m.unavailable());
            if (m.likes() != null) row.put("likes", m.likes());
            if (m.comments() != null) row.put("comments", m.comments());
            if (m.shares() != null) row.put("shares", m.shares());
            rows.add(row);
        }
        return ActionResult.ok(Map.of("metrics", rows));
    }

    ActionResult get(Map<String, Object> input, ConnectionContext ctx) {
        String postId = MetaActions.string(input, "post_id");
        if (postId == null) {
            return ActionResult.error("get_facebook_post requires 'post_id'");
        }
        String token = MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }

        try {
            if (isReelId(postId)) {
                // Reels don't answer the Page-post fields readPost asks for; status.video_status is the
                // Reel's own answer to "has it gone live yet?", shaped here into the same is_published
                // output key so PlatformLiveness#facebookIsLive reads it exactly as a feed post's answer.
                MetaGraphClient.VideoStatus status = graphClient.readVideoStatus(postId, token);
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("post_id", status.id());
                output.put("is_published", status.published());
                if (status.permalink() != null) {
                    output.put("permalink", status.permalink());
                }
                return ActionResult.ok(output);
            }

            MetaGraphClient.PagePost post = graphClient.readPost(postId, token);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("post_id", post.id());
            output.put("is_published", post.published());
            if (post.permalink() != null) {
                output.put("permalink", post.permalink());
            }
            if (post.scheduledPublishTime() != null) {
                output.put("scheduled_publish_time", post.scheduledPublishTime().toString());
            }
            return ActionResult.ok(output);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Facebook could not read post " + postId);
        }
    }

    /**
     * A Story: exactly one photo or video, published immediately (Meta cannot schedule a Story), and
     * with no caption — Instagram's Facebook counterpart ignores one, so it is dropped rather than sent
     * and merely logged when the destination carried one.
     */
    private ActionResult publishStory(String pageId, String token, List<PublishMedia> media, String message,
                                      Long scheduledPublishTime) {
        if (media.size() != 1) {
            return ActionResult.error("A Facebook Story needs exactly one photo or video; this destination "
                    + "selected " + media.size() + " item(s)");
        }
        if (scheduledPublishTime != null) {
            log.debug("Facebook Stories cannot be scheduled; publishing immediately instead");
        }
        if (message != null && !message.isBlank()) {
            log.info("Facebook Stories carry no caption; the message on this post was not sent");
        }

        PublishMedia only = media.get(0);
        try {
            MetaGraphClient.PublishedPost published;
            if (only.isVideo()) {
                published = graphClient.publishVideoStory(pageId, token, only.url());
            } else {
                String photoId = graphClient.uploadUnpublishedPhoto(pageId, token, only.url());
                published = graphClient.publishPhotoStory(pageId, token, photoId);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("post_id", published.postId());
            output.put("is_story", true);
            return ActionResult.ok(output);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Facebook rejected the story");
        }
    }

    /**
     * Picks the post shape from what this destination selected: several photos, a single photo, or text
     * alone. A single video never reaches here — it is routed to the Reels API before this is called,
     * since Meta no longer accepts a Page video any other way.
     */
    private MetaGraphClient.PublishedPost dispatch(String pageId, String token, List<PublishMedia> media,
                                                   String message, String link, String title,
                                                   Long scheduledPublishTime) {
        if (media.size() == 1) {
            return graphClient.publishPhoto(pageId, token, media.get(0).url(), message, scheduledPublishTime);
        }
        if (media.size() > 1) {
            // A multi-photo post is a feed story with the photos attached, so each one is uploaded
            // unpublished first and the feed post ties them together. The /photos edge cannot express it:
            // it makes one post per photo.
            List<String> photoIds = media.stream()
                    .map(photo -> graphClient.uploadUnpublishedPhoto(pageId, token, photo.url()))
                    .toList();
            return graphClient.publishFeedPostWithAttachedMedia(pageId, token, message, photoIds,
                    scheduledPublishTime);
        }
        return graphClient.publishFeedPost(pageId, token, message, link, scheduledPublishTime);
    }

    private ActionResult success(MetaGraphClient.PublishedPost published, String token, String pageId,
                                 Long scheduledPublishTime) {
        String postId = published.postId();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", postId);
        String permalink = permalinkFor(postId, token);
        if (permalink == null) {
            permalink = derivedPermalink(postId, pageId);
        }
        if (permalink != null) {
            output.put("permalink", permalink);
        }
        output.put("scheduled", scheduledPublishTime != null);
        if (scheduledPublishTime != null) {
            output.put("scheduled_publish_time", Instant.ofEpochSecond(scheduledPublishTime).toString());
        }
        return ActionResult.ok(output);
    }

    /** As {@link #success}, for a published Reel: its permalink comes from {@link #readVideoStatus}, not a Page-post read. */
    private ActionResult reelSuccess(MetaGraphClient.PublishedPost published, String token, Long scheduledPublishTime) {
        String videoId = published.postId();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("post_id", videoId);
        String permalink = reelPermalinkFor(videoId, token);
        if (permalink != null) {
            output.put("permalink", permalink);
        }
        output.put("scheduled", scheduledPublishTime != null);
        if (scheduledPublishTime != null) {
            output.put("scheduled_publish_time", Instant.ofEpochSecond(scheduledPublishTime).toString());
        }
        return ActionResult.ok(output);
    }

    /**
     * Best-effort read-back of the permalink. The post already exists at this point, so a failure to
     * read its URL must not turn a successful publish into a failure the caller would retry — it falls
     * back to the id-derived URL instead.
     */
    private String permalinkFor(String postId, String token) {
        try {
            String permalink = graphClient.readPost(postId, token).permalink();
            return permalink != null && !permalink.isBlank() ? permalink : null;
        } catch (RuntimeException e) {
            log.debug("Could not read permalink for Facebook post {}: {}", postId, e.getMessage());
            return null;
        }
    }

    /** As {@link #permalinkFor}, reading a just-published Reel's permalink via {@code status.video_status}. */
    private String reelPermalinkFor(String videoId, String token) {
        try {
            String permalink = graphClient.readVideoStatus(videoId, token).permalink();
            return permalink != null && !permalink.isBlank() ? permalink : null;
        } catch (RuntimeException e) {
            log.debug("Could not read permalink for Facebook reel {}: {}", videoId, e.getMessage());
            return null;
        }
    }

    /**
     * Facebook Page post ids are {@code {page-id}_{post-id}}, and that pair is exactly the public URL —
     * so a post whose {@code permalink_url} Meta withholds (it does for one it has not published yet)
     * still gets a working link.
     */
    private static String derivedPermalink(String postId, String pageId) {
        if (postId == null) {
            return null;
        }
        int separator = postId.indexOf('_');
        if (separator > 0 && separator < postId.length() - 1) {
            return "https://www.facebook.com/" + postId.substring(0, separator)
                    + "/posts/" + postId.substring(separator + 1);
        }
        return pageId != null ? "https://www.facebook.com/" + pageId + "/posts/" + postId : null;
    }

    /**
     * Whether {@code id} names a Reel video rather than a Page post: a Page post id is always
     * {@code {page-id}_{post-id}}, while a video id (a Reel's included) is a bare numeric string with no
     * underscore — the same shape {@link #derivedPermalink} already relies on.
     */
    private static boolean isReelId(String id) {
        return id != null && id.indexOf('_') < 0;
    }

    /**
     * The fire time as unix seconds, or null to publish immediately. Null is returned for a time already
     * in the past as well as for an absent one: Meta refuses a past {@code scheduled_publish_time}, and
     * a late post going out now beats one that never goes out at all.
     */
    private static Long futureScheduleSeconds(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return null;
        }
        Instant fireTime = parseInstant(isoInstant.trim());
        return fireTime.isAfter(Instant.now()) ? fireTime.getEpochSecond() : null;
    }

    /**
     * The scheduler always sends a UTC {@code Z} instant, but a hand-authored workflow step may well write
     * an offset-bearing timestamp, which {@code Instant.parse} alone rejects. Both are the same moment, so
     * both are accepted.
     */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }
}
