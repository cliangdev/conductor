package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The optional, user-typed connection settings of the PostHog connector, validated strictly. Nothing here
 * is ever concatenated into HogQL as raw text: the host is parsed as a URL, the event filter is split into
 * a property name and a literal that travel as query placeholders, and the conversion event is matched
 * against a whitelist pattern.
 *
 * @param host            base URL, no trailing slash
 * @param filterProperty  property name of the optional {@code property = 'value'} filter, or null
 * @param filterValue     literal of that filter, or null
 * @param conversionEvent event counted as {@code conversion_clicks}, or null when not configured
 */
record PostHogSettings(String host, String filterProperty, String filterValue, String conversionEvent) {

    static final String DEFAULT_HOST = "https://app.posthog.com";
    static final String US_CLOUD = "https://us.posthog.com";
    static final String EU_CLOUD = "https://eu.posthog.com";

    private static final String NAME = "[A-Za-z0-9_$.:-]{1,100}";
    static final Pattern EVENT_NAME = Pattern.compile("^" + NAME + "$");
    /** {@code property = 'literal'}: the literal may hold anything but quotes and backslashes. */
    static final Pattern FILTER = Pattern.compile("^\\s*(" + NAME + ")\\s*=\\s*'([^'\\\\\\r\\n]{0,200})'\\s*$");

    static PostHogSettings from(ConnectionContext ctx) {
        String host = resolveHost(text(ctx, "host"));
        String filter = text(ctx, "eventFilter");
        String filterProperty = null;
        String filterValue = null;
        if (filter != null) {
            Matcher m = FILTER.matcher(filter);
            if (!m.matches()) {
                throw ChannelMetricsSourceException.setupRequired(
                        "Event filter must look like  property = 'value'  (for example $lib = 'web')");
            }
            filterProperty = m.group(1);
            filterValue = m.group(2);
        }
        String conversion = text(ctx, "conversionEvent");
        if (conversion != null && !EVENT_NAME.matcher(conversion).matches()) {
            throw ChannelMetricsSourceException.setupRequired(
                    "Conversion event may only contain letters, digits and _ $ . : - (max 100 characters)");
        }
        return new PostHogSettings(host, filterProperty, filterValue, conversion);
    }

    /** Just the host of a connection, for the calls that need no other setting. */
    static String hostOf(ConnectionContext ctx) {
        return resolveHost(text(ctx, "host"));
    }

    static String resolveHost(String raw) {
        if (raw == null) {
            return DEFAULT_HOST;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("us")) return US_CLOUD;
        if (lower.equals("eu")) return EU_CLOUD;
        try {
            URI uri = URI.create(raw);
            boolean plainRoot = uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !plainRoot) {
                throw new IllegalArgumentException("bad host");
            }
            return "https://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw ChannelMetricsSourceException.setupRequired(
                    "PostHog host must be US, EU or a self-hosted https URL such as https://posthog.example.com");
        }
    }

    private static String text(ConnectionContext ctx, String key) {
        Object v = ctx.configValue(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
