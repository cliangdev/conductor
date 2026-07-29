package com.conductor.integration;

/**
 * Thrown when an OAuth2 {@code refresh_token} grant fails with the provider's {@code invalid_grant}
 * error — the refresh token itself is permanently dead (expired or revoked), so retrying the same
 * refresh will never succeed. Callers should stop retrying and surface that the connection needs to
 * go through the authorization flow again, rather than treating this like a transient failure.
 */
public class OAuthReauthRequiredException extends RuntimeException {
    public OAuthReauthRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
