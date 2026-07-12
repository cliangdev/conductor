package com.conductor.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implemented by connectors that authenticate via the shared OAuth2 authorization-code flow
 * ({@link com.conductor.service.OAuthFlowService}). The flow service looks the connector up in the
 * {@link ConnectorRegistry} and asks it for the scopes, endpoints, and credential config to drive the
 * consent screen and token exchange — none of that lives in the flow service itself.
 *
 * <p>The default methods describe Google's OAuth2 flow, since every connector today authenticates
 * against Google. A non-Google provider overrides all five: {@link #authorizationUrl()},
 * {@link #tokenUrl()}, {@link #clientIdProperty()}, {@link #clientSecretProperty()}, and
 * {@link #extraAuthorizationParams()}.
 */
public interface OAuth2Connector extends Connector {
    /** OAuth2 scopes this connector needs, e.g. {@code https://www.googleapis.com/auth/webmasters.readonly}. */
    List<String> oauthScopes();

    /** Authorization endpoint the user is redirected to for consent. */
    default String authorizationUrl() {
        return "https://accounts.google.com/o/oauth2/v2/auth";
    }

    /** Token endpoint used for both the authorization-code exchange and refresh-token grant. */
    default String tokenUrl() {
        return "https://oauth2.googleapis.com/token";
    }

    /** Name of the environment/config property holding this provider's OAuth client ID. */
    default String clientIdProperty() {
        return "GOOGLE_OAUTH_CLIENT_ID";
    }

    /** Name of the environment/config property holding this provider's OAuth client secret. */
    default String clientSecretProperty() {
        return "GOOGLE_OAUTH_CLIENT_SECRET";
    }

    /** Extra query params appended to the consent URL, in iteration order. */
    default Map<String, String> extraAuthorizationParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        return params;
    }
}
