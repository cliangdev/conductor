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
 * against Google. A non-Google provider overrides {@link #authorizationUrl()}, {@link #tokenUrl()},
 * {@link #clientIdProperty()}, {@link #clientSecretProperty()}, and {@link #extraAuthorizationParams()}.
 *
 * <p><b>Every method here defaults to the exact behaviour the flow service had before it was
 * generalized.</b> That is the contract: a connector that overrides nothing produces a byte-for-byte
 * identical consent URL and token body to the pre-seam implementation, so adding a hook can never
 * perturb an existing Google connector.
 *
 * <p>Three families of hook exist beyond the endpoint declarations:
 * <ul>
 *   <li><b>Parameter naming</b> — {@link #clientIdParamName()} and {@link #scopeDelimiter()}, for
 *       providers that deviate from RFC 6749's spelling (TikTok names the client identifier
 *       {@code client_key} and requires comma-separated scopes).</li>
 *   <li><b>Post-exchange completion</b> — {@link #completeAuthorization(OAuthCompletionRequest)},
 *       where a connector does provider-specific work after the code exchange (swapping for a
 *       longer-lived token, reading the account identity) and contributes non-secret connection
 *       config.</li>
 *   <li><b>Account selection</b> — {@link #requiresAccountSelection()} and
 *       {@link #listAuthorizableAccounts(String)}, for providers whose grant covers several
 *       publishable accounts and where a human must pick one before the connection is usable.</li>
 * </ul>
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

    /**
     * Name the provider gives the client-identifier parameter, on both the consent URL and the
     * token/refresh request bodies. RFC 6749 calls it {@code client_id} and nearly every provider
     * follows; TikTok calls it {@code client_key}. The client <i>secret</i> parameter is
     * {@code client_secret} everywhere encountered so far, so it has no equivalent hook.
     */
    default String clientIdParamName() {
        return "client_id";
    }

    /**
     * Delimiter used to join {@link #oauthScopes()} into the consent URL's {@code scope} parameter.
     * RFC 6749 specifies a space; TikTok's {@code /v2/auth/authorize/} requires a comma and silently
     * grants only part of the request otherwise.
     */
    default String scopeDelimiter() {
        return " ";
    }

    /** Extra query params appended to the consent URL, in iteration order. */
    default Map<String, String> extraAuthorizationParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        return params;
    }

    /**
     * One account the authorizing grant covers, for the post-consent account picker. Carries only
     * non-secret identity — a per-account credential (a Meta Page access token, say) is deliberately
     * withheld here and only materialized inside
     * {@link #completeAuthorization(OAuthCompletionRequest)}, which routes it to the encrypted slot.
     *
     * @param id    provider-side identifier the admin's choice is expressed as
     * @param label human-readable name shown in the picker
     */
    record OAuthAccount(String id, String label) {}

    /**
     * What the flow service hands the completion hook: the credentials the code exchange just
     * produced, plus the account the admin picked (null when the connector needs no selection, or
     * when the callback runs before the picker).
     */
    record OAuthCompletionRequest(String accessToken, String refreshToken, String selectedAccountId) {}

    /**
     * What the completion hook contributes back. The split is the whole point: {@link #accessToken()}
     * and {@link #refreshToken()} are credentials and are persisted through
     * {@code ConnectionService.storeTokens} (per-connection DEK envelope encryption), while
     * {@link #config()} is plaintext JSON on the connection row and must therefore hold only
     * non-secret identifiers.
     *
     * <p>A null {@code accessToken}/{@code refreshToken} means "keep what the code exchange
     * returned" — which is what the no-op default reports, leaving today's behaviour untouched.
     *
     * @param label optional display label for the connection, so two accounts on one platform stay
     *              distinguishable in the UI; null keeps the default label
     */
    record OAuthCompletion(String accessToken, String refreshToken, String label, Map<String, Object> config) {
        public OAuthCompletion {
            config = config == null ? Map.of() : Map.copyOf(config);
        }

        /** The no-op outcome: keep the exchanged tokens, contribute no config. */
        public static OAuthCompletion unchanged() {
            return new OAuthCompletion(null, null, null, Map.of());
        }

        public static OAuthCompletion of(String accessToken, String label, Map<String, Object> config) {
            return new OAuthCompletion(accessToken, null, label, config);
        }
    }

    /**
     * Provider-specific work after the shared authorization-code exchange — swapping a short-lived
     * token for a long-lived one, resolving the account identity, reading per-account capabilities.
     *
     * <p>Default is a no-op that keeps the exchanged tokens and contributes no config, so a connector
     * that does not implement it (every Google connector today) behaves exactly as before.
     *
     * <p>Throwing from here fails the whole connect rather than leaving a half-built connection: a
     * connection whose account identity could not be established can never publish.
     */
    default OAuthCompletion completeAuthorization(OAuthCompletionRequest request) {
        return OAuthCompletion.unchanged();
    }

    /**
     * Whether a human must choose which of several accounts this grant maps to before the connection
     * is usable — Meta, whose grant covers every Page the user administers, is the case this exists
     * for. Connectors whose grant resolves exactly one account (YouTube's {@code mine=true}, TikTok's
     * creator) leave this false and complete in the callback.
     *
     * <p>Deliberately declarative rather than derived from {@link ConnectorSpec#fields()}: the
     * existing Google connectors also declare required user-input fields (GSC's {@code siteUrl},
     * gcp-billing's {@code bqDatasetName}), but those are picked from their own dashboards <i>after</i>
     * the connection is complete, not as a gate on it. Inferring from the spec would reroute their
     * callbacks and change behaviour that must not change.
     */
    default boolean requiresAccountSelection() {
        return false;
    }

    /**
     * Accounts the grant covers, for the picker. Only called when {@link #requiresAccountSelection()}
     * is true; the default returns none so an ordinary single-account connector needs no override.
     */
    default List<OAuthAccount> listAuthorizableAccounts(String accessToken) {
        return List.of();
    }
}
