package com.conductor.integration.connector.posthog;

import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The optional, user-typed connection settings of the PostHog connector, validated strictly. Nothing here
 * is ever concatenated into HogQL as raw text except the internal-users cohort id: the host is parsed as a
 * URL, the event filter is split into a property name and a literal, the conversion event is matched against
 * a whitelist pattern, and the excluded path prefixes are matched against a whitelist pattern, all of which
 * travel as query placeholders. HogQL only accepts a cohort as a literal ({@code NOT IN COHORT 339420}), so
 * that one value is spliced, and is safe only because it is checked to be 1 to 12 ASCII digits first.
 *
 * @param host                base URL, no trailing slash
 * @param filterProperty      property name of the optional {@code property = 'value'} filter, or null
 * @param filterValue         literal of that filter, or null
 * @param conversionEvent     event counted as {@code conversion_clicks}, or null when not configured
 * @param internalCohortId    digits-only id of the cohort of team members left out of every number, or null
 * @param excludePathPrefixes page paths (share pages) whose events are left out of every number; empty when
 *                            none are configured, never holds a blank entry
 */
record PostHogSettings(String host, String filterProperty, String filterValue, String conversionEvent,
                       String internalCohortId, List<String> excludePathPrefixes) {

    static final String DEFAULT_HOST = "https://app.posthog.com";
    static final String US_CLOUD = "https://us.posthog.com";
    static final String EU_CLOUD = "https://eu.posthog.com";

    private static final String NAME = "[A-Za-z0-9_$.:-]{1,100}";
    static final Pattern EVENT_NAME = Pattern.compile("^" + NAME + "$");
    /** {@code property = 'literal'}: the literal may hold anything but quotes and backslashes. */
    static final Pattern FILTER = Pattern.compile("^\\s*(" + NAME + ")\\s*=\\s*'([^'\\\\\\r\\n]{0,200})'\\s*$");
    /** The only text of the settings that is spliced into HogQL: a cohort id is digits and nothing else. */
    static final Pattern COHORT_ID = Pattern.compile("^\\d{1,12}$");
    static final Pattern PATH_PREFIX = Pattern.compile("^/[A-Za-z0-9/_.-]{0,100}$");
    static final int MAX_PATH_PREFIXES = 10;

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
        return new PostHogSettings(host, filterProperty, filterValue, conversion,
                cohortId(text(ctx, "internalCohortId")), pathPrefixes(text(ctx, "excludePathPrefixes")));
    }

    private static String cohortId(String raw) {
        if (raw == null) return null;
        if (!COHORT_ID.matcher(raw).matches()) {
            throw ChannelMetricsSourceException.setupRequired(
                    "Internal users cohort ID must be digits only, the number in the cohort's PostHog URL "
                            + "(for example 339420)");
        }
        return raw;
    }

    /** Comma-separated, each entry trimmed; blank entries are dropped because an empty prefix matches every page. */
    private static List<String> pathPrefixes(String raw) {
        if (raw == null) return List.of();
        List<String> prefixes = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .distinct()
                .toList();
        for (String prefix : prefixes) {
            if (!PATH_PREFIX.matcher(prefix).matches()) {
                throw ChannelMetricsSourceException.setupRequired(
                        "Excluded page prefixes must be comma-separated paths that start with / and use only "
                                + "letters, digits and / _ . - (max 100 characters each), for example /share, /s/");
            }
            if (prefix.equals("/")) {
                throw ChannelMetricsSourceException.setupRequired(
                        "An excluded page prefix of / would exclude every page; use a longer prefix such as /share");
            }
        }
        if (prefixes.size() > MAX_PATH_PREFIXES) {
            throw ChannelMetricsSourceException.setupRequired(
                    "At most " + MAX_PATH_PREFIXES + " excluded page prefixes are allowed");
        }
        return prefixes;
    }

    /** Just the host of a connection, for the calls that need no other setting. */
    static String hostOf(ConnectionContext ctx) {
        return resolveHost(text(ctx, "host"));
    }

    /** The message for a key PostHog refuses (401/403) on a project: what to create, and where. */
    static String refusedKeyMessage(Object projectId) {
        return "PostHog refused the key for project " + projectId
                + ": use a Personal API key (phx_…) with query:read access to that project";
    }

    /**
     * Blank means the default (US cloud). {@code us} / {@code eu} pick the cloud region; anything else is a
     * self-hosted https host, typed with or without the scheme and with or without a trailing slash.
     */
    static String resolveHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_HOST;
        }
        String trimmed = raw.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("us")) return US_CLOUD;
        if (lower.equals("eu")) return EU_CLOUD;
        String withScheme = lower.contains("://") ? trimmed : "https://" + trimmed;
        try {
            URI uri = URI.create(withScheme);
            String path = uri.getPath();
            boolean plainRoot = path == null || path.isEmpty() || path.matches("/+");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !plainRoot) {
                throw new IllegalArgumentException("bad host");
            }
            String hostName = uri.getHost().toLowerCase(Locale.ROOT);
            if (isInternalName(hostName)) {
                throw ChannelMetricsSourceException.setupRequired(
                        "PostHog host must be a public hostname, not an IP address or localhost");
            }
            return "https://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        } catch (ChannelMetricsSourceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw ChannelMetricsSourceException.setupRequired(
                    "PostHog host must be US, EU or a self-hosted https URL such as https://posthog.example.com");
        }
    }

    /** IP literals (v4 or v6) and localhost names are never a PostHog host; resolved addresses are checked at verify. */
    private static boolean isInternalName(String host) {
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.startsWith("[") || host.contains(":")
                || host.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || host.matches("\\d+") || host.matches("(0x[0-9a-f]+)(\\.[0-9a-fx]+)*");
    }

    private static String text(ConnectionContext ctx, String key) {
        Object v = ctx.configValue(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
