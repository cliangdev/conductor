package com.conductor.integration.connector.meta;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Account-level stats for the SOCIAL channel from a Meta connection: Facebook Page followers and post count,
 * Instagram followers, media count and daily website clicks. Levels are snapshots written for the pull's
 * {@code to} day only; nothing is back-filled. Uses only the scopes the connector already requests: a platform
 * whose read is refused (4xx) is skipped with a WARN, and the pull fails as setup-required only when nothing
 * at all could be read. A rate limit or 5xx is a transient failure and degrades the whole pull.
 */
final class MetaChannelMetrics {

    private static final Logger log = LoggerFactory.getLogger(MetaChannelMetrics.class);

    static final String FACEBOOK = "facebook";
    static final String INSTAGRAM = "instagram";
    /** Instagram insights allow at most a 30-day window per request; stay inside it. */
    static final int WEBSITE_CLICKS_BACKFILL_DAYS = 28;

    private static final AtomicBoolean WEBSITE_CLICKS_UNSUPPORTED_LOGGED = new AtomicBoolean();

    private final MetaGraphClient client;

    MetaChannelMetrics(MetaGraphClient client) {
        this.client = client;
    }

    List<ChannelMetricPoint> pullDaily(ConnectionContext ctx, LocalDate from, LocalDate to) {
        String token = ctx == null ? null : ctx.accessToken();
        String pageId = text(ctx, MetaConnector.CONFIG_PAGE_ID);
        String igId = text(ctx, MetaConnector.CONFIG_IG_ACCOUNT_ID);
        if (token == null || token.isBlank() || (pageId == null && igId == null)) {
            throw ChannelMetricsSourceException.setupRequired(
                    "Reconnect Meta and pick a Facebook Page to read account stats");
        }

        List<ChannelMetricPoint> points = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        boolean readSomething = false;

        if (pageId != null) {
            try {
                JsonNode page = client.readAccountFields(pageId, "fan_count,followers_count", token);
                Double followers = number(page, "followers_count");
                if (followers == null) followers = number(page, "fan_count");
                if (followers != null) {
                    points.add(ChannelMetricPoint.of("followers", FACEBOOK, to, followers));
                    readSomething = true;
                }
                try {
                    Long posts = client.readPublishedPostCount(pageId, token);
                    if (posts != null) {
                        points.add(ChannelMetricPoint.of("media_count", FACEBOOK, to, posts));
                    }
                } catch (HttpClientErrorException e) {
                    if (isTransient(e)) throw e;
                    log.info("Facebook post count not readable for Page {} ({}); skipping media_count",
                            pageId, e.getStatusCode().value());
                }
            } catch (HttpClientErrorException e) {
                skipOrFail(e, "Facebook Page " + pageId, refusals);
            }
        }

        if (igId != null) {
            try {
                JsonNode ig = client.readAccountFields(igId, "followers_count,media_count", token);
                Double followers = number(ig, "followers_count");
                Double media = number(ig, "media_count");
                if (followers != null) {
                    points.add(ChannelMetricPoint.of("followers", INSTAGRAM, to, followers));
                    readSomething = true;
                }
                if (media != null) {
                    points.add(ChannelMetricPoint.of("media_count", INSTAGRAM, to, media));
                    readSomething = true;
                }
                if (readWebsiteClicks(igId, token, from, to, points)) {
                    readSomething = true;
                }
            } catch (HttpClientErrorException e) {
                skipOrFail(e, "Instagram account " + igId, refusals);
            }
        }

        if (!readSomething) {
            throw ChannelMetricsSourceException.setupRequired(refusals.isEmpty()
                    ? "Meta returned no account stats for this connection"
                    : "Meta refused the account stats read (" + String.join("; ", refusals)
                            + "). Reconnect Meta and grant the requested permissions");
        }
        return points;
    }

    /** Daily website clicks; a deprecated or refused metric is skipped, never a failure. True when a series was read. */
    private boolean readWebsiteClicks(String igId, String token, LocalDate from, LocalDate to,
                                      List<ChannelMetricPoint> points) {
        LocalDate since = from.isBefore(to.minusDays(WEBSITE_CLICKS_BACKFILL_DAYS))
                ? to.minusDays(WEBSITE_CLICKS_BACKFILL_DAYS) : from;
        try {
            JsonNode values = client.readDailyInsight(igId, "website_clicks", since, to.plusDays(1), token);
            if (values == null || !values.isArray()) {
                logUnsupportedOnce("Graph returned no daily series for it");
                return false;
            }
            for (JsonNode entry : values) {
                JsonNode value = entry.path("value");
                String end = entry.path("end_time").asText(null);
                if (!value.isNumber() || end == null) continue;
                // end_time is the close of the measured day (midnight Pacific), so the data belongs to the day before.
                LocalDate day = OffsetDateTime.parse(end.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2"))
                        .withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().minusDays(1);
                if (day.isBefore(since) || day.isAfter(to)) continue;
                points.add(ChannelMetricPoint.of("website_clicks", INSTAGRAM, day, value.asDouble()));
            }
            return true;
        } catch (HttpClientErrorException e) {
            if (isTransient(e)) throw e;
            String body = e.getResponseBodyAsString() == null ? "" : e.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            if (body.contains("deprecat") || body.contains("no longer") || body.contains("metric_type")
                    || body.contains("not supported") || body.contains("does not support")) {
                logUnsupportedOnce("Graph says it is deprecated or unsupported");
            } else {
                log.warn("Instagram website_clicks refused for {} ({}); skipping it", igId, e.getStatusCode().value());
            }
            return false;
        }
    }

    private static void logUnsupportedOnce(String reason) {
        if (WEBSITE_CLICKS_UNSUPPORTED_LOGGED.compareAndSet(false, true)) {
            log.info("Instagram website_clicks is not available in this Graph API version ({}); recording nothing for it", reason);
        }
    }

    private static void skipOrFail(HttpClientErrorException e, String what, List<String> refusals) {
        if (isTransient(e)) throw e;
        log.warn("Meta refused the stats read for {} ({}); skipping that platform", what, e.getStatusCode().value());
        refusals.add(what + " " + e.getStatusCode().value());
    }

    private static boolean isTransient(HttpClientErrorException e) {
        return e.getStatusCode().value() == 429;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private static String text(ConnectionContext ctx, String key) {
        Object value = ctx == null ? null : ctx.configValue(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
