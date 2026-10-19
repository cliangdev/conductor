package com.conductor.integration.connector.tiktok;

import com.conductor.entity.Connection;
import com.conductor.integration.OAuthReauthRequiredException;

/**
 * Where {@link TikTokCreatorInfoService} gets a creator's live profile from. A seam so the {@code local}
 * profile — which has no TikTok to call — can answer from the connection's cached config instead.
 */
public interface TikTokCreatorInfoSource {

    /**
     * The creator behind {@code connection}, as TikTok reports it right now.
     *
     * @throws OAuthReauthRequiredException when the connection's token is dead and a person has to reconnect
     */
    TikTokClient.CreatorInfo query(Connection connection);
}
