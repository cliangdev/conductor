package com.conductor.marketing.switchback;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.integration.MetricChannel;
import com.conductor.repository.ChannelMetricDailyRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reads the two daily series a switchback test settles on, from {@code channel_metric_daily}: App Store first-time search
 * downloads ({@code STORE downloads} at {@code source:APP_STORE_SEARCH}) and Apple Ads tap first-time downloads
 * ({@code ADS new_downloads}, day total). A day counts only when it is settled: it has at least one row and none of its
 * rows (one per connection) is provisional. Rows of several connections on one day are summed, as the overview does.
 *
 * <p>Nothing here guesses. A day with no row, or only provisional rows, is listed as missing for that series, never read as
 * 0; the caller waits until {@link Daily#missing()} is empty.
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

    private final ChannelMetricDailyRepository repository;

    SwitchbackDataReader(ChannelMetricDailyRepository repository) {
        this.repository = repository;
    }

    /** The settled days of both series over every planned week, and what is still missing. */
    Daily read(String projectId, List<SwitchbackWeek> weeks) {
        LocalDate from = weeks.get(0).weekStart();
        LocalDate to = weeks.get(weeks.size() - 1).weekEnd();
        Map<LocalDate, List<ChannelMetricDaily>> store = byDay(repository
                .findByProjectIdAndChannelAndDimensionKeyAndDayBetween(projectId, MetricChannel.STORE.name(),
                        SEARCH_SOURCE, from, to), STORE_METRIC);
        Map<LocalDate, List<ChannelMetricDaily>> ads = byDay(repository
                .findByProjectIdAndChannelAndDimensionKeyAndDayBetween(projectId, MetricChannel.ADS.name(), "", from, to),
                ADS_METRIC);

        Map<LocalDate, BigDecimal> search = settled(store);
        Map<LocalDate, BigDecimal> paid = settled(ads);
        List<String> missing = new ArrayList<>();
        addMissing(missing, "App Store search first-time downloads", from, to, search);
        addMissing(missing, "Apple Ads first-time downloads", from, to, paid);
        return new Daily(search, paid, missing);
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
