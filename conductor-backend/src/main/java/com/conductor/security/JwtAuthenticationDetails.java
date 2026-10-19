package com.conductor.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * The request details of a JWT-authenticated call, plus where the token came from.
 *
 * <p>A JWT can arrive in the {@code Authorization} header or in the {@code access_token} cookie. Both
 * authenticate the same user, but only the header proves the caller's own script chose to send it: a
 * cookie is attached by the browser to <em>any</em> request to this origin, including one a third-party
 * page triggered. {@code ProjectSecurityService#requireInteractiveUser} therefore treats a header-borne
 * token as the human's own action and a cookie-only one as not — which keeps cross-site request forgery
 * out of consent writes without a CSRF token (the web client always sends the header).
 */
public class JwtAuthenticationDetails extends WebAuthenticationDetails {

    private final boolean fromAuthorizationHeader;

    public JwtAuthenticationDetails(HttpServletRequest request, boolean fromAuthorizationHeader) {
        super(request);
        this.fromAuthorizationHeader = fromAuthorizationHeader;
    }

    public boolean isFromAuthorizationHeader() {
        return fromAuthorizationHeader;
    }
}
