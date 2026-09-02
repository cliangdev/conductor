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
import java.util.Map;

/**
 * The three Facebook Page actions: publish/schedule a post, take one back down, and read whether a
 * scheduled one has gone live.
 *
 * <h2>Media is resolved here, not handed in</h2>
 * The publishing scheduler's payload deliberately carries only the copy plus {@code work_item_id} /
 * {@code target_id} handles. Turning those into a URL Meta can fetch is this class's job, and the
 * signed read URL is minted <b>inside this invocation</b> ({@link PublishMediaResolver#resolve}) rather
 * than at approval time: Meta fetches the bytes during the publish call, and a URL minted when a human
 * approved the post days earlier would have expired by the fire time.
 *
 * <h2>Scheduling</h2>
 * A future fire time goes out as {@code published=false} plus a unix {@code scheduled_publish_time},
 * which is the only shape Meta honours — the timestamp alone is silently ignored and the post goes out
 * immediately. A fire time already in the past publishes now instead, because Meta rejects a
 * {@code scheduled_publish_time} in the past and the post is already late.
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

        PublishMedia media;
        String explicitVideoUrl = MetaActions.string(input, "video_url");
        String explicitImageUrl = MetaActions.string(input, "image_url");
        try {
            media = MetaActions.resolveMedia(mediaResolver, input, explicitImageUrl, explicitVideoUrl);
        } catch (RuntimeException e) {
            // Media resolution is local (asset lookup + signing); a failure here is this post's own
            // problem and will not fix itself on a retry.
            log.warn("Could not resolve Facebook media for work item {}: {}",
                    MetaActions.string(input, "work_item_id"), e.getMessage());
            return ActionResult.error("Could not resolve media for this post: " + e.getMessage());
        }

        boolean hasText = (message != null && !message.isBlank()) || (link != null && !link.isBlank());
        if (media == null && !hasText) {
            return ActionResult.error("publish_facebook_post needs a message, a link, or media on the Work Item");
        }

        try {
            MetaGraphClient.PublishedPost published = dispatch(pageId, token, media, message, link,
                    MetaActions.string(input, "title"), scheduledPublishTime);
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

    ActionResult get(Map<String, Object> input, ConnectionContext ctx) {
        String postId = MetaActions.string(input, "post_id");
        if (postId == null) {
            return ActionResult.error("get_facebook_post requires 'post_id'");
        }
        String token = MetaActions.tokenOrNull(ctx);
        if (token == null) {
            return ActionResult.error("Meta connection has no Page access token; reconnect the Page");
        }

        MetaGraphClient.PagePost post;
        try {
            post = graphClient.readPost(postId, token);
        } catch (HttpClientErrorException e) {
            return MetaActions.permanentOrRethrow(e, "Facebook could not read post " + postId);
        }

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
    }

    private MetaGraphClient.PublishedPost dispatch(String pageId, String token, PublishMedia media,
                                                   String message, String link, String title,
                                                   Long scheduledPublishTime) {
        if (media != null && media.isVideo()) {
            if (media.gcsPath() == null) {
                // A caller-supplied hosted URL has no bytes here to chunk; Meta fetches it itself.
                return graphClient.publishVideoFromUrl(pageId, token, media.url(), message, title,
                        scheduledPublishTime);
            }
            byte[] content = mediaResolver.download(media);
            return graphClient.publishVideo(pageId, token, content, message, title, scheduledPublishTime);
        }
        if (media != null) {
            return graphClient.publishPhoto(pageId, token, media.url(), message, scheduledPublishTime);
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
