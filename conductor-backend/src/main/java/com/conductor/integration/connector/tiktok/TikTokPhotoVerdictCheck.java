package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.integration.ConnectionContext;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.conductor.integration.connector.tiktok.TikTokPublishAction.UploadCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The read side of the late TikTok verdict check ({@code PublishTaskKind#TIKTOK_VERDICT}): a photo post
 * whose publish attempt gave up while TikTok had not yet downloaded its images may still be published
 * later, because TikTok's download queue can stall for hours and then drain. This asks TikTok for the
 * recorded {@code publish_id}'s status, using the invocation checkpoint the failed attempt left behind.
 * Reads only (plus clearing the link expiry once TikTok has abandoned the post).
 */
@Component
public class TikTokPhotoVerdictCheck {

    private static final Logger log = LoggerFactory.getLogger(TikTokPhotoVerdictCheck.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What the failed attempt recorded: the post TikTok holds and how long its image links stay live. */
    public record Pending(String publishId, Instant linksExpireAt, Instant initAt) {}

    /** One reading: TikTok's status plus the post id and link when it is complete. */
    public record Reading(TikTokClient.PublishStatus status, String postId, String permalink) {}

    private final TikTokClient client;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final OAuthFlowService oAuthFlowService;
    private final ActionInvocationService actionInvocationService;

    @Autowired
    public TikTokPhotoVerdictCheck(ConnectionRepository connectionRepository, ConnectionService connectionService,
                                   OAuthFlowService oAuthFlowService,
                                   @Lazy ActionInvocationService actionInvocationService) {
        this(new TikTokClient(), connectionRepository, connectionService, oAuthFlowService,
                actionInvocationService);
    }

    TikTokPhotoVerdictCheck(TikTokClient client, ConnectionRepository connectionRepository,
                            ConnectionService connectionService, OAuthFlowService oAuthFlowService,
                            ActionInvocationService actionInvocationService) {
        this.client = client;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.oAuthFlowService = oAuthFlowService;
        this.actionInvocationService = actionInvocationService;
    }

    /**
     * The photo post the target's failed attempt left open, or empty when there is nothing to check: not a
     * TikTok target, no checkpoint, not a photo post, or the link expiry was cleared (TikTok abandoned the
     * post, or the checkpoint predates the field).
     */
    public Optional<Pending> pending(PostPublishTarget target) {
        return checkpoint(target)
                .filter(c -> c.isPhotoPost() && c.publishId() != null && !c.publishId().isBlank()
                        && c.linksExpireInstant() != null)
                .map(c -> new Pending(c.publishId(), c.linksExpireInstant(), c.initInstant()));
    }

    /** Asks TikTok for {@code publishId}'s status with a fresh token. Throws on a TikTok or credential failure. */
    public Reading read(PostPublishTarget target, String publishId) {
        Connection connection = target.getConnectionId() == null ? null
                : connectionRepository.findById(target.getConnectionId()).orElse(null);
        if (connection == null) {
            throw new IllegalStateException("TikTok connection for target " + target.getId() + " no longer exists");
        }
        ConnectionContext ctx = oAuthFlowService.withFreshToken(connection, connectionService.toContext(connection));
        TikTokClient.PublishStatus status = client.fetchPublishStatus(ctx.accessToken(), publishId);
        if (!status.complete()) {
            return new Reading(status, null, null);
        }
        String postId = status.postId() == null || status.postId().isBlank() ? null : status.postId();
        String permalink = status.shareUrl() != null && !status.shareUrl().isBlank() ? status.shareUrl() : null;
        if (permalink == null && postId != null) {
            Object username = ctx.configValue(TikTokConnector.CONFIG_CREATOR_USERNAME);
            permalink = TikTokPermalinks.url(username == null ? null : String.valueOf(username).trim(),
                    TikTokPermalinks.KIND_PHOTO, postId);
        }
        return new Reading(status, postId, permalink);
    }

    /**
     * Clears the link expiry on the target's checkpoint: TikTok has rejected the post, so its still-live
     * links cannot publish a duplicate and the manual-retry guard may let a retry through at once.
     */
    public void releaseLinks(PostPublishTarget target) {
        Optional<UploadCheckpoint> checkpoint = checkpoint(target);
        if (checkpoint.isEmpty()) {
            return;
        }
        try {
            actionInvocationService.saveCheckpoint(target.getIdempotencyKey(),
                    MAPPER.writeValueAsString(checkpoint.get().withLinksReleased()));
        } catch (Exception e) {
            log.warn("Could not release the photo links on the checkpoint of target {}: {}", target.getId(),
                    e.getMessage());
        }
    }

    private Optional<UploadCheckpoint> checkpoint(PostPublishTarget target) {
        if (target == null || target.getIdempotencyKey() == null || actionInvocationService == null) {
            return Optional.empty();
        }
        return actionInvocationService.readCheckpoint(target.getIdempotencyKey()).flatMap(json -> {
            try {
                return Optional.of(MAPPER.readValue(json, UploadCheckpoint.class));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
