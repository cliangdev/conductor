package com.conductor.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Guards outbound requests to a user-supplied URL (server-side request forgery). Requires https and
 * refuses any host that resolves to a loopback, private (RFC 1918), link-local (169.254.0.0/16, which
 * includes the cloud metadata address), unique-local (fc00::/7), wildcard or multicast address. The
 * {@code local} profile relaxes both rules so a developer can point at a server on localhost.
 *
 * <p>The check resolves the host at validation time; a hostname that changes its answer between the
 * check and the connection (DNS rebinding) is a residual risk, mitigated by callers never following
 * redirects.
 */
@Component
public class OutboundUrlGuard {

    private final boolean permissive;

    @Autowired
    public OutboundUrlGuard(Environment environment) {
        this(environment.acceptsProfiles(Profiles.of("local")));
    }

    /** {@code permissive=true} allows http and private addresses; production beans are strict. */
    public OutboundUrlGuard(boolean permissive) {
        this.permissive = permissive;
    }

    /** Validate and return the parsed URI, or throw {@link IllegalArgumentException} with a plain message. */
    public URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The server URL is required.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The server URL is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(permissive && scheme.equals("http"))) {
            throw new IllegalArgumentException("The server URL must start with https://.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("The server URL is not a valid URL.");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("The server URL must not contain a username or password.");
        }
        if (permissive) return uri;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not find the server host \"" + host + "\".");
        }
        for (InetAddress a : addresses) {
            if (isBlocked(a)) {
                throw new IllegalArgumentException(
                        "The server URL points at a private or internal address, which is not allowed.");
            }
        }
        return uri;
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress()
                || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16) {
            // Unique-local fc00::/7 (isSiteLocalAddress only covers the deprecated fec0::/10).
            return (b[0] & 0xFE) == 0xFC;
        }
        if (b.length == 4) {
            // Carrier-grade NAT 100.64.0.0/10 and the "this network" block 0.0.0.0/8.
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return (first == 100 && second >= 64 && second <= 127) || first == 0;
        }
        return false;
    }
}
