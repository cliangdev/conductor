package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.integration.ConnectionContext;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.publish.PlatformPending;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The read side of the late TikTok verdict check ({@code PublishTaskKind#TIKTOK_VERDICT}): a photo post
 * TikTok accepted but had not yet downloaded (the target waits as {@code HANDED_OFF}) may be published
 * later, because TikTok's download queue can stall for hours and then drain. This asks TikTok for the
 * recorded {@code publish_id}'s status. Reads only.
 */
@Component
public class TikTokPhotoVerdictCheck {

    /** What the pending marker on the target records: the post TikTok holds and how long its image links stay live. */
    public record Pending(String publishId, Instant linksExpireAt, Instant initAt) {}

    /** One reading: TikTok's status plus the post id and link when it is complete. */
    public record Reading(TikTokClient.PublishStatus status, String postId, String permalink) {}

    private final TikTokClient client;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final OAuthFlowService oAuthFlowService;

    @Autowired
    public TikTokPhotoVerdictCheck(ConnectionRepository connectionRepository, ConnectionService connectionService,
                                   OAuthFlowService oAuthFlowService) {
        this(new TikTokClient(), connectionRepository, connectionService, oAuthFlowService);
    }

    TikTokPhotoVerdictCheck(TikTokClient client, ConnectionRepository connectionRepository,
                            ConnectionService connectionService, OAuthFlowService oAuthFlowService) {
        this.client = client;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.oAuthFlowService = oAuthFlowService;
    }

    /**
     * The photo post TikTok accepted but had not published, as {@link PlatformPending} left it on the
     * target; empty when the row carries none (a retried or settled row).
     */
    public Optional<Pending> pending(PostPublishTarget target) {
        return target == null ? Optional.empty()
                : PlatformPending.parse(target.getResumeCheckpoint())
                        .map(p -> new Pending(p.publishId(), p.linksExpireAt(), p.initAt()));
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
}
