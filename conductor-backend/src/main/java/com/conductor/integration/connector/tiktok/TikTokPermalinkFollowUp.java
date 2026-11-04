package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.integration.ConnectionContext;
import com.conductor.repository.ConnectionRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.publish.PermalinkFollowUp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Asks TikTok again for a published post's link. {@code status/fetch} reports PUBLISH_COMPLETE before the
 * post has an id; the id appears only after moderation, and only for public posts. Not profile-gated
 * (unlike the publish action) because the follow-up only reads.
 */
@Component
public class TikTokPermalinkFollowUp {

    /** What a successful follow-up found. */
    public record Found(String postId, String permalink) {}

    private final TikTokClient client;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final OAuthFlowService oAuthFlowService;

    @Autowired
    public TikTokPermalinkFollowUp(ConnectionRepository connectionRepository, ConnectionService connectionService,
                                   OAuthFlowService oAuthFlowService) {
        this(new TikTokClient(), connectionRepository, connectionService, oAuthFlowService);
    }

    TikTokPermalinkFollowUp(TikTokClient client, ConnectionRepository connectionRepository,
                            ConnectionService connectionService, OAuthFlowService oAuthFlowService) {
        this.client = client;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.oAuthFlowService = oAuthFlowService;
    }

    /** Empty while TikTok still has no id for the post. Throws on a TikTok or credential failure. */
    public Optional<Found> check(PostPublishTarget target, PermalinkFollowUp followUp) {
        Connection connection = target.getConnectionId() == null ? null
                : connectionRepository.findById(target.getConnectionId()).orElse(null);
        if (connection == null) {
            return Optional.empty();
        }
        ConnectionContext ctx = oAuthFlowService.withFreshToken(connection, connectionService.toContext(connection));
        TikTokClient.PublishStatus status = client.fetchPublishStatus(ctx.accessToken(), followUp.publishId());
        if (!status.complete()) {
            return Optional.empty();
        }
        String postId = status.postId() == null || status.postId().isBlank() ? null : status.postId();
        String permalink = status.shareUrl() != null && !status.shareUrl().isBlank() ? status.shareUrl() : null;
        if (permalink == null && postId != null) {
            Object username = ctx.configValue(TikTokConnector.CONFIG_CREATOR_USERNAME);
            permalink = TikTokPermalinks.url(username == null ? null : String.valueOf(username).trim(),
                    followUp.kind(), postId);
        }
        return permalink == null ? Optional.empty() : Optional.of(new Found(postId, permalink));
    }
}
