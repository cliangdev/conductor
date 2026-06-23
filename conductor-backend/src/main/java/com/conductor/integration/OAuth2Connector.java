package com.conductor.integration;

import java.util.List;

/**
 * Implemented by connectors that authenticate via the shared Google OAuth2 authorization-code flow
 * ({@link com.conductor.service.OAuthFlowService}). Declaring scopes here — rather than hardcoding
 * them in the flow service — is the extension point that lets a new OAuth2 connector request its own
 * Google scopes without touching shared auth code. The flow service looks the connector up in the
 * {@link ConnectorRegistry} and asks it for the scopes to put on the consent URL.
 */
public interface OAuth2Connector extends Connector {
    /** Google OAuth2 scopes this connector needs, e.g. {@code https://www.googleapis.com/auth/webmasters.readonly}. */
    List<String> oauthScopes();
}
