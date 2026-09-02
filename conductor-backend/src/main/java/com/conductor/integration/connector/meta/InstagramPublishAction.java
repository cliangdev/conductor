package com.conductor.integration.connector.meta;

import com.conductor.integration.ActionResult;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.meta.MetaConnector.MetaActions;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import com.conductor.integration.connector.meta.MetaConnector.PublishMediaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
 * <b>before</b> the container. Exhaustion is reported as {@link ActionResult#error} — PERMANENT for this
 * attempt, because the retry budget is minutes and the quota window is hours; a human reschedules.
 *
 * <h2>Failure classification</h2>
 * Same contract as {@link FacebookPublishAction}: 4xx (except {@code 429}) → {@link ActionResult#error}
 * (permanent, dead-lettered); 5xx and network errors propagate as thrown exceptions (transient, retried).
 */
class InstagramPublishAction {

    private static final Logger log = LoggerFactory.getLogger(InstagramPublishAction.class);

    static final String ACTION_PUBLISH = "publish_instagram_media";

    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    private static final String MEDIA_TYPE_REELS = "REELS";

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

        PublishMedia media;
        String explicitImageUrl = MetaActions.string(input, "image_url");
        String explicitVideoUrl = MetaActions.string(input, "video_url");
        try {
            media = MetaActions.resolveMedia(mediaResolver, input, explicitImageUrl, explicitVideoUrl);
        } catch (RuntimeException e) {
            log.warn("Could not resolve Instagram media for work item {}: {}",
                    MetaActions.string(input, "work_item_id"), e.getMessage());
            return ActionResult.error("Could not resolve media for this post: " + e.getMessage());
        }
        if (media == null || media.url() == null || media.url().isBlank()) {
            return ActionResult.error("publish_instagram_media needs an image or video — Instagram has no text-only post");
        }

        String mediaType = mediaType(MetaActions.string(input, "media_type"), media);

        try {
            MetaGraphClient.PublishingLimit limit = graphClient.readContentPublishingLimit(igUserId, token);
            if (limit.exhausted()) {
                return ActionResult.error("Instagram publishing limit reached: this account has used "
                        + limit.quotaUsage() + " of its " + limit.quotaTotal()
                        + "-posts-per-24-hours cap, so no container can be created. Reschedule this post for "
                        + "after the rolling 24-hour window clears.");
            }

            String containerId = graphClient.createMediaContainer(igUserId, token,
                    containerParams(mediaType, media, MetaActions.string(input, "caption")));

            if (!MEDIA_TYPE_IMAGE.equals(mediaType)) {
                ActionResult stuck = awaitContainer(containerId, token);
                if (stuck != null) {
                    return stuck;
                }
            }

            String mediaId = graphClient.publishMediaContainer(igUserId, token, containerId);

            Map<String, Object> output = new LinkedHashMap<>();
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

    private static Map<String, String> containerParams(String mediaType, PublishMedia media, String caption) {
        Map<String, String> params = new LinkedHashMap<>();
        if (MEDIA_TYPE_IMAGE.equals(mediaType)) {
            params.put("image_url", media.url());
        } else {
            params.put("video_url", media.url());
            params.put("media_type", mediaType);
        }
        if (caption != null && !caption.isBlank()) {
            params.put("caption", caption);
        }
        return params;
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
