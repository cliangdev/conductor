package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The real source: refreshes the connection's token if it is stale, then asks TikTok. */
@Component
@Profile("!local")
public class TikTokApiCreatorInfoSource implements TikTokCreatorInfoSource {

    private final OAuthFlowService oAuthFlowService;
    private final ConnectionService connectionService;
    private final TikTokClient client = new TikTokClient();

    public TikTokApiCreatorInfoSource(OAuthFlowService oAuthFlowService, ConnectionService connectionService) {
        this.oAuthFlowService = oAuthFlowService;
        this.connectionService = connectionService;
    }

    @Override
    public TikTokClient.CreatorInfo query(Connection connection) {
        // A TikTok access token lives 24 hours, so the stored one is routinely stale by the time a person
        // opens the consent page.
        var ctx = oAuthFlowService.withFreshToken(connection, connectionService.toContext(connection));
        return client.queryCreatorInfo(ctx.accessToken());
    }
}
