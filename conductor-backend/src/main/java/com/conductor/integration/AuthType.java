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
    /** Redirect-based delegated auth with token refresh (GCP Billing, Slack). */
    OAUTH2,
    /** Inbound push; the "credential" is a platform-generated signing secret (GitHub). */
    WEBHOOK
}
