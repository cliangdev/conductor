package com.conductor.integration;

/**
 * How a connector acquires its primary credential.
 * Kept minimal-now / extensible-later: add modes lazily as a connector demands them
 * (e.g. OAUTH2_CC, APP, JWT) rather than speculatively.
 */
public enum AuthType {
    /** No credential (e.g. a public-data pull). */
    NONE,
    /** User pastes a key/token directly (PostHog). */
    API_KEY,
    /** Username + password / secret pair. */
    BASIC,
    /** User pastes/uploads a provider service-account JSON key (GCP). Stored encrypted like API_KEY. */
    SERVICE_ACCOUNT,
    /** Redirect-based delegated auth with token refresh (GCP Billing, Slack). */
    OAUTH2,
    /** Inbound push; the "credential" is a platform-generated signing secret (GitHub repo webhook). */
    WEBHOOK,
    /** Installed app (e.g. GitHub App): the user installs a vendor-owned app and picks resources on the
     *  provider; the app authenticates with its own key, and the connection stores an installation id. */
    APP,
    /** User pastes a Personal Access Token directly (e.g. GitHub fine-grained PAT). Distinct from
     *  {@link #API_KEY}: a PAT has real provider-enforced expiry semantics, surfaced via
     *  {@code Connection.tokenExpiresAt}, and its own UI label ("Personal Access Token" vs "API Key"). */
    PAT
}
