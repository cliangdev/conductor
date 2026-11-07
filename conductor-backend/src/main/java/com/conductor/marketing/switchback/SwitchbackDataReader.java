package com.conductor.marketing.switchback;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.Connection;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.connector.applesearchads.AppleSearchAdsConnector;
import com.conductor.integration.ingest.ChannelMetricsFeedPuller;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reads the two daily series a switchback test settles on, from {@code channel_metric_daily}: App Store first-time search
 * downloads ({@code STORE downloads} at {@code source:APP_STORE_SEARCH}) and Apple Ads tap first-time downloads
 * ({@code ADS new_downloads}, day total). A day counts only when it is settled: it has at least one row and none of its
 * rows (one per connection) is provisional. Rows of several connections on one day are summed, as the overview does.
 *
 * <p>Nothing here guesses. A day with no row, or only provisional rows, is listed as missing for that series, never read as
 * 0; the caller waits until {@link Daily#missing()} is empty.
 *
 * <p>One exception, for Apple Ads only. Apple returns no record for a day with no ad activity (the connector asks with
 * {@code returnRecordsWithNoMetrics=false}), so an off week that pauses the account's only campaign never gets a row. Such
 * a day reads as 0 when there is positive evidence the source reported nothing for it: the day has no stored point at
 * all in the Apple Ads totals (a day with spend but no {@code new_downloads} field is unknown, not 0), and every Apple Ads
 * connection of the project has a feed whose last pull walked to a day at least
 * {@value AppleSearchAdsConnector#TRAILING_DAYS} days after it (past the days Apple may still restate), starting no
 * earlier than the connection's first stored point (when its first pull began is not recorded). See
 * {@link ChannelMetricsFeedPuller#walkCompletedThrough}.
 */
@Component
class SwitchbackDataReader {

    static final String SEARCH_SOURCE = "source:APP_STORE_SEARCH";
    private static final String STORE_METRIC = "downloads";
    private static final String ADS_METRIC = "new_downloads";
    private static final int LISTED_DAYS = 3;

    /** Settled sums by day; {@code missing} says, in words, which days of which series are not settled yet. */
    record Daily(Map<LocalDate, BigDecimal> searchDownloads, Map<LocalDate, BigDecimal> adsNewDownloads,
                 List<String> missing) {}

    private static final String ADS_CONNECTOR = "apple-search-ads";
    private static final String ADS_FEED = "channel_metrics";

    private final ChannelMetricDailyRepository repository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorFeedRepository feedRepository;
    private final ObjectMapper objectMapper;

    SwitchbackDataReader(ChannelMetricDailyRepository repository, ConnectionRepository connectionRepository,
                         ConnectorFeedRepository feedRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.feedRepository = feedRepository;
        this.objectMapper = objectMapper;
    }

    /** A span of days one connection's feed is known to have read, both ends inclusive. */
    private record Covered(LocalDate from, LocalDate to) {
        boolean contains(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /** The settled days of both series over every planned week, and what is still missing. */
    Daily read(String projectId, List<SwitchbackWeek> weeks) {
        LocalDate from = weeks.get(0).weekStart();
        LocalDate to = weeks.get(weeks.size() - 1).weekEnd();
        Map<LocalDate, List<ChannelMetricDaily>> store = byDay(repository
                .findByProjectIdAndChannelAndDimensionKeyAndDayBetween(projectId, MetricChannel.STORE.name(),
                        SEARCH_SOURCE, from, to), STORE_METRIC);
        List<ChannelMetricDaily> adsTotals = repository
                .findByProjectIdAndChannelAndDimensionKeyAndDayBetween(projectId, MetricChannel.ADS.name(), "", from, to);
        Map<LocalDate, List<ChannelMetricDaily>> ads = byDay(adsTotals, ADS_METRIC);

        Map<LocalDate, BigDecimal> search = settled(store);
        Map<LocalDate, BigDecimal> paid = settled(ads);
        zeroCoveredQuietDays(projectId, from, to, adsTotals, paid);
        List<String> missing = new ArrayList<>();
        addMissing(missing, "App Store search first-time downloads", from, to, search);
        addMissing(missing, "Apple Ads first-time downloads", from, to, paid);
        return new Daily(search, paid, missing);
    }

    /**
     * Reads every day in {@code from..to} that has no Apple Ads total point of any metric as 0, when the project's Apple
     * Ads feeds all cover it. A day that has any point (even a provisional one, or only {@code spend}) is never touched.
     */
    private void zeroCoveredQuietDays(String projectId, LocalDate from, LocalDate to,
                                      List<ChannelMetricDaily> adsTotals, Map<LocalDate, BigDecimal> paid) {
        List<Covered> coverage = adsCoverage(projectId);
        if (coverage.isEmpty()) {
            return;
        }
        java.util.Set<LocalDate> daysWithPoints = adsTotals.stream().map(ChannelMetricDaily::getDay)
                .collect(Collectors.toSet());
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!daysWithPoints.contains(day)) {
                LocalDate d = day;
                if (coverage.stream().allMatch(c -> c.contains(d))) {
                    paid.put(day, BigDecimal.ZERO);
                }
            }
        }
    }

    /**
     * One span per Apple Ads connection of the project; empty (so nothing is ever 0) when there is no such connection or
     * any of them has no finished pull or no stored point to anchor the span's start.
     */
    private List<Covered> adsCoverage(String projectId) {
        List<Connection> connections = connectionRepository.findByProjectIdAndConnectorId(projectId, ADS_CONNECTOR);
        List<Covered> out = new ArrayList<>();
        for (Connection connection : connections) {
            LocalDate first = repository.firstDay(connection.getId());
            Optional<LocalDate> pulledThrough = feedRepository.findByConnectionIdAndIngestId(connection.getId(), ADS_FEED)
                    .map(ConnectorFeed::getCursorState)
                    .map(cursor -> ChannelMetricsFeedPuller.walkCompletedThrough(cursor, objectMapper));
            if (first == null || pulledThrough.isEmpty()) {
                return List.of();
            }
            out.add(new Covered(first, pulledThrough.get().minusDays(AppleSearchAdsConnector.TRAILING_DAYS)));
        }
        return out;
    }

    private static Map<LocalDate, List<ChannelMetricDaily>> byDay(List<ChannelMetricDaily> rows, String metric) {
        return rows.stream().filter(r -> metric.equals(r.getMetric()))
                .collect(Collectors.groupingBy(ChannelMetricDaily::getDay));
    }

    private static Map<LocalDate, BigDecimal> settled(Map<LocalDate, List<ChannelMetricDaily>> rowsByDay) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        rowsByDay.forEach((day, rows) -> {
            if (rows.stream().noneMatch(ChannelMetricDaily::isProvisional)) {
                out.put(day, rows.stream().map(ChannelMetricDaily::getValue).filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        });
        return out;
    }

    private static void addMissing(List<String> missing, String label, LocalDate from, LocalDate to,
                                   Map<LocalDate, BigDecimal> settled) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!settled.containsKey(day)) {
                days.add(day);
            }
        }
        if (days.isEmpty()) {
            return;
        }
        String listed = days.stream().limit(LISTED_DAYS).map(LocalDate::toString).collect(Collectors.joining(", "));
        missing.add(label + " on " + listed + (days.size() > LISTED_DAYS ? " and " + (days.size() - LISTED_DAYS) + " more" : ""));
    }
}
