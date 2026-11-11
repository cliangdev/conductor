package com.conductor.marketing.verification;

import com.conductor.exception.BusinessException;
import com.conductor.integration.MetricChannel;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ConnectionView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Snapshot;
import com.conductor.marketing.overview.MarketingOverviewQueryService.SnapshotChannel;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Window;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.overview.MetricCatalog.Aggregation;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The verification ledger: for each metric of every connected channel that can be checked, Conductor's own value,
 * the product backend's value over the same settled days, the latest direct platform read, and which of them to
 * trust. Everything but the reads themselves is computed on read from the overview's snapshot, so the ledger can
 * never disagree with the overview's own MISMATCH status.
 *
 * <p><b>Verdicts</b> (per channel metric): {@code BOTH} every second source available agrees with the connector;
 * {@code CONNECTOR} trust the connector (a platform read agrees with it, or the backend disagrees and no read can
 * be compared yet); {@code BACKEND} the backend and the platform read agree and the connector does not;
 * {@code PLATFORM} the platform read disagrees with every other source; {@code UNCHECKED} no second source at all,
 * never verified. {@code mismatch} is true when any second source that could be compared disagrees with the
 * connector, so only {@code BOTH} is ever {@linkplain #isVerified verified}. Figures are judged by
 * {@link ReconciliationBand}, over settled days only.
 *
 * <p><b>Reads</b> are never deleted: this service adds them, lists them (retracted ones too, as the audit trail)
 * and can {@linkplain #retract retract} a wrong one, after which it counts nowhere and the previous read of that
 * metric (if any) applies.
 * A read also remembers the channel's connections when it was recorded and counts only while at least one of them
 * is still connected, so disconnecting a channel and connecting another account never compares the new account with
 * a figure read from the old one (reads recorded before that was tracked always count).
 * A read covers an explicit range of days; it is compared with Conductor's figure over exactly those days (a
 * level: its value on the last day; unique visitors: the stored count for a window of that length ending on the
 * last day). A read over days Conductor has not settled yet cannot be compared and counts for nothing, not for a
 * match and not for a mismatch.
 */
@Service
public class VerificationLedgerService {

    public enum Verdict { BOTH, CONNECTOR, BACKEND, PLATFORM, UNCHECKED }

    /** A read recorded more than this many days ago is stale: the platform may have restated since. */
    public static final int STALE_READ_DAYS = 7;
    static final Set<Integer> ROLLING_WINDOWS = Set.of(7, 28, 90);
    static final int MAX_SOURCE_NAME = 120;
    static final int MAX_NOTE = 500;
    static final int DEFAULT_HISTORY = 100;
    static final int MAX_HISTORY = 500;

    /** The product backend's figure against Conductor's, over the settled days both have in the window. */
    public record BackendFigure(double value, double conductorValue, int daysCompared, String source, LocalDate from,
                                LocalDate to, boolean matches) {}

    /**
     * One recorded read. {@code conductorValueOverReadDays} and {@code matches} are null when Conductor cannot say
     * (it has not settled the read's days, or unique visitors were read over a length that is not stored).
     */
    public record PlatformReadFigure(String id, double value, LocalDate from, LocalDate to, String sourceName,
                                     String note, String recordedBy, OffsetDateTime recordedAt,
                                     Double conductorValueOverReadDays, Boolean matches) {}

    public record LedgerRow(String channel, String metric, String metricRef, String label, Unit unit,
                            MetricCatalog.Definition definition, LocalDate windowFrom, LocalDate windowTo,
                            Double conductorValue, BackendFigure backend, PlatformReadFigure platformRead,
                            Verdict verdict, boolean mismatch, boolean readStale, String trustNote) {}

    public record Summary(int both, int connector, int backend, int platform, int unchecked, int mismatched,
                          int staleReads) {}

    /** {@code to} is null when no channel has data yet. */
    public record Ledger(LocalDate from, LocalDate to, List<LedgerRow> rows, Summary summary) {

        /** The row of a {@code CHANNEL.metric} reference (the channel is case-insensitive), if there is one. */
        public Optional<LedgerRow> find(String metricRef) {
            String normalized = normalizeRef(metricRef);
            return rows.stream().filter(r -> r.metricRef().equals(normalized)).findFirst();
        }
    }

    /** The read just recorded and the recomputed ledger row for exactly its days. */
    public record RecordedRow(PlatformReadFigure read, LedgerRow row) {}

    /** One stored read with what Conductor and the backend showed when it was recorded. */
    public record ReadEntry(String id, String channel, String metric, LocalDate from, LocalDate to, double value,
                            String sourceName, String note, String recordedBy, OffsetDateTime recordedAt,
                            Double conductorValue, Double backendValue, String verdict,
                            OffsetDateTime retractedAt, String retractedBy, String retractionReason) {}

    private final MarketingOverviewQueryService overviewService;
    private final MarketingPlatformReadRepository readRepository;

    public VerificationLedgerService(MarketingOverviewQueryService overviewService,
                                     MarketingPlatformReadRepository readRepository) {
        this.overviewService = overviewService;
        this.readRepository = readRepository;
    }

    // ---- reads --------------------------------------------------------------------------------------

    /**
     * The ledger for a window: {@code 7d}, {@code 28d} (the default), {@code 90d}, or a fixed range of days
     * {@code YYYY-MM-DD..YYYY-MM-DD}. A rolling window ends where each channel's data does; every row says which
     * days its Conductor value covers.
     */
    @Transactional(readOnly = true)
    public Ledger ledger(String projectId, String windowRaw) {
        return ledger(projectId, windowRaw, LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /**
     * The ledger over a snapshot the caller already built for the same window, so a caller that has one (the
     * marketing report) does not pay for a second pass over the data.
     */
    @Transactional(readOnly = true)
    public Ledger ledger(String projectId, Snapshot snapshot, OffsetDateTime now) {
        return compute(snapshot, latestReads(projectId), now);
    }

    /** Package-visible so a test can pin "today" and "now". */
    Ledger ledger(String projectId, String windowRaw, LocalDate today, OffsetDateTime now) {
        Snapshot snapshot = overviewService.snapshot(projectId, windowRaw, ROLLING_WINDOWS, today, now);
        return compute(snapshot, latestReads(projectId), now);
    }

    /** The ledger row of one {@code CHANNEL.metric}, or empty when the channel is not connected or the metric is not checkable. */
    @Transactional(readOnly = true)
    public Optional<LedgerRow> row(String projectId, String metricRef, String windowRaw) {
        return ledger(projectId, windowRaw).find(metricRef);
    }

    Optional<LedgerRow> row(String projectId, String metricRef, String windowRaw, LocalDate today,
                            OffsetDateTime now) {
        return ledger(projectId, windowRaw, today, now).find(metricRef);
    }

    /**
     * True when a second source confirmed the metric and none disagrees: its verdict is not UNCHECKED and it is not
     * a mismatch. A metric with no row, no second source or any disagreement is not verified. Callers that ask
     * about many metrics should read {@link #ledger} once and use {@link #isVerified}.
     */
    @Transactional(readOnly = true)
    public boolean verified(String projectId, String metricRef, String windowRaw) {
        return row(projectId, metricRef, windowRaw).map(VerificationLedgerService::isVerified).orElse(false);
    }

    boolean verified(String projectId, String metricRef, String windowRaw, LocalDate today, OffsetDateTime now) {
        return row(projectId, metricRef, windowRaw, today, now).map(VerificationLedgerService::isVerified)
                .orElse(false);
    }

    /** Verdict in {BOTH, CONNECTOR, BACKEND, PLATFORM} and no mismatch; UNCHECKED or any mismatch is not verified. */
    public static boolean isVerified(LedgerRow row) {
        return row.verdict() != Verdict.UNCHECKED && !row.mismatch();
    }

    /** The recorded reads, newest first, optionally of one channel and one metric. */
    @Transactional(readOnly = true)
    public List<ReadEntry> reads(String projectId, String channelRaw, String metric, Integer limit) {
        String channel = channelRaw == null || channelRaw.isBlank() ? null : requireChannel(channelRaw).name();
        String metricKey = metric == null || metric.isBlank() ? null : metric.trim();
        PageRequest page = PageRequest.of(0, limit == null ? DEFAULT_HISTORY : Math.max(1, Math.min(limit, MAX_HISTORY)),
                Sort.by(Sort.Direction.DESC, "recordedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        List<MarketingPlatformRead> found = channel != null && metricKey != null
                ? readRepository.findByProjectIdAndChannelAndMetric(projectId, channel, metricKey, page)
                : channel != null ? readRepository.findByProjectIdAndChannel(projectId, channel, page)
                : metricKey != null ? readRepository.findByProjectIdAndMetric(projectId, metricKey, page)
                : readRepository.findByProjectId(projectId, page);
        return found.stream().map(VerificationLedgerService::toEntry).toList();
    }

    // ---- recording ----------------------------------------------------------------------------------

    /**
     * Records what a platform's own UI or API showed for one metric over {@code from}..{@code to} and returns the
     * ledger row recomputed for exactly those days. The metric must be a sum, a level or window-unique visitors of
     * a connected channel; the range must end yesterday or earlier and span at most
     * {@value MarketingOverviewQueryService#FIXED_WINDOW_MAX_DAYS} days; the value must be zero or more.
     * Conductor's figure, the backend's and the verdict as of now are stored with the read. Never deleted.
     */
    @Transactional
    public RecordedRow record(String projectId, String channelRaw, String metricKey, LocalDate from, LocalDate to,
                              double value, String sourceName, String note, String userId) {
        return record(projectId, channelRaw, metricKey, from, to, value, sourceName, note, userId,
                LocalDate.now(ZoneOffset.UTC), OffsetDateTime.now());
    }

    /** Package-visible so a test can pin "today" and "now" (the moment the read is recorded). */
    RecordedRow record(String projectId, String channelRaw, String metricKey, LocalDate from, LocalDate to,
                       double value, String sourceName, String note, String userId, LocalDate today,
                       OffsetDateTime now) {
        MetricChannel channel = requireChannel(channelRaw);
        validateRange(from, to, today);
        if (!Double.isFinite(value) || value < 0) {
            throw new BusinessException("A read's value must be a number of zero or more");
        }
        String source = sourceName == null ? "" : sourceName.trim();
        if (source.isEmpty()) {
            throw new BusinessException("sourceName is required: say where the figure was read");
        }
        if (source.length() > MAX_SOURCE_NAME) {
            throw new BusinessException("sourceName is at most " + MAX_SOURCE_NAME + " characters");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new BusinessException("note is at most " + MAX_NOTE + " characters");
        }

        // The snapshot for exactly the read's days, so the returned row is Conductor's figure over those days.
        Snapshot snapshot = overviewService.snapshot(projectId, from + ".." + to, ROLLING_WINDOWS, today, now);
        SnapshotChannel snapshotChannel = snapshot.channels().get(channel);
        if (snapshotChannel == null || !isConnected(snapshotChannel.view())) {
            throw new BusinessException("Channel " + channel.name() + " has no connection, so there is nothing to"
                    + " check a read against");
        }
        Metric metric = snapshotChannel.catalog().visibleMetrics().stream()
                .filter(m -> m.key().equals(metricKey)).findFirst()
                .orElseThrow(() -> new BusinessException("Unknown metric '" + metricKey + "' for channel "
                        + channel.name()));
        if (metric.agg() == Aggregation.DERIVED) {
            throw new BusinessException(channel.name() + "." + metric.key() + " is a ratio of two other metrics;"
                    + " read its parts directly instead");
        }
        // A distinct count is stored for a few window lengths only; a read of any other length could never be
        // reconciled, so it is refused rather than stored.
        long readDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (!metric.supportsWindowOf(readDays)) {
            throw new BusinessException(channel.name() + "." + metric.key() + " is a distinct count, so it can only"
                    + " be read over a window of " + MetricCatalog.describeWindowDays(metric.supportedWindowDays())
                    + " days; this read covers " + readDays + " days");
        }

        // The connections the read is checked against: it stops counting once none of them is connected.
        String[] connectionIds = snapshotChannel.view().connections().stream().map(ConnectionView::id)
                .toArray(String[]::new);
        OffsetDateTime recordedAt = now.truncatedTo(ChronoUnit.MICROS);
        String id = UUID.randomUUID().toString();
        MarketingPlatformRead candidate = new MarketingPlatformRead(id, projectId, channel.name(), metric.key(), from,
                to, BigDecimal.valueOf(value), source, cleanNote, userId, recordedAt, null, null, null, connectionIds);
        Map<String, MarketingPlatformRead> reads = latestReads(projectId);
        String ref = channel.name() + "." + metric.key();
        reads.put(ref, candidate);
        LedgerRow row = compute(snapshot, reads, now).find(ref).orElseThrow();

        Double backendOverRead = backendOver(snapshot, channel, metric, from, to).orElse(null);
        Double conductorOverRead = row.platformRead() == null ? null : row.platformRead().conductorValueOverReadDays();
        readRepository.save(new MarketingPlatformRead(id, projectId, channel.name(), metric.key(), from, to,
                candidate.getValue(), source, cleanNote, userId, recordedAt,
                conductorOverRead == null ? null : BigDecimal.valueOf(conductorOverRead),
                backendOverRead == null ? null : BigDecimal.valueOf(backendOverRead), row.verdict().name(), connectionIds));
        return new RecordedRow(row.platformRead(), row);
    }

    // ---- retracting ---------------------------------------------------------------------------------

    /**
     * Retracts a read as wrong: it stays in the history with who and why, but no longer counts, so the previous
     * non-retracted read of that metric (if any) applies. One-way and idempotent: retracting a retracted read
     * returns it unchanged (who and why are not overwritten).
     */
    @Transactional
    public ReadEntry retract(String projectId, String readId, String reason, String actorUserId) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new BusinessException("reason is required: say why the read is wrong");
        }
        if (why.length() > MAX_NOTE) {
            throw new BusinessException("reason is at most " + MAX_NOTE + " characters");
        }
        MarketingPlatformRead existing = readRepository.findByIdAndProjectId(readId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Platform read not found"));
        if (existing.getRetractedAt() != null) {
            return toEntry(existing);
        }
        readRepository.retract(readId, projectId, OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS), actorUserId,
                why);
        return toEntry(readRepository.findByIdAndProjectId(readId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Platform read not found")));
    }

    private static void validateRange(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null) {
            throw new BusinessException("A read needs the first and last day it covers");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("A read starts on " + from + ", after it ends on " + to
                    + "; give the first day, then the last");
        }
        LocalDate yesterday = today.minusDays(1);
        if (to.isAfter(yesterday)) {
            throw new BusinessException("A read must end yesterday (" + yesterday + ") or earlier: today is still"
                    + " partial, so the platform and Conductor would not be showing the same days");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MarketingOverviewQueryService.FIXED_WINDOW_MAX_DAYS) {
            throw new BusinessException("A read of " + days + " days is too long; read at most "
                    + MarketingOverviewQueryService.FIXED_WINDOW_MAX_DAYS + " days at a time");
        }
    }

    private static MetricChannel requireChannel(String raw) {
        return MetricCatalog.channel(raw).map(MetricCatalog.Channel::channel)
                .orElseThrow(() -> new BusinessException("Unknown channel '" + raw + "'; expected one of "
                        + MetricCatalog.channels().stream().map(c -> c.channel().name()).toList()));
    }

    // ---- computing the ledger -----------------------------------------------------------------------

    private Map<String, MarketingPlatformRead> latestReads(String projectId) {
        Map<String, MarketingPlatformRead> byRef = new HashMap<>();
        for (MarketingPlatformRead read : readRepository.findLatestByProject(projectId)) {
            byRef.put(read.getChannel() + "." + read.getMetric(), read);
        }
        return byRef;
    }

    private static boolean isConnected(ChannelView view) {
        return !view.status().equals("NOT_CONNECTED") && !view.status().equals("COMING_SOON");
    }

    private Ledger compute(Snapshot snapshot, Map<String, MarketingPlatformRead> reads, OffsetDateTime now) {
        List<LedgerRow> rows = new ArrayList<>();
        for (SnapshotChannel channel : snapshot.channels().values()) {
            ChannelView view = channel.view();
            if (!isConnected(view)) {
                continue;
            }
            for (MetricView shown : view.metrics()) {
                Metric metric = channel.catalog().metric(shown.metric()).orElse(null);
                if (metric == null || metric.hidden() || metric.agg() == Aggregation.DERIVED) {
                    continue;
                }
                String ref = view.channel() + "." + metric.key();
                rows.add(row(snapshot, channel, metric, shown, reads.get(ref), now));
            }
        }
        Window window = snapshot.overview().window();
        return new Ledger(window.from(), window.to(), rows, summarize(rows));
    }

    private LedgerRow row(Snapshot snapshot, SnapshotChannel channel, Metric metric, MetricView shown,
                          MarketingPlatformRead read, OffsetDateTime now) {
        MetricChannel id = channel.catalog().channel();
        boolean percentOnly = MarketingOverviewQueryService.judgedByPercentOnly(metric);
        Window window = channel.window();

        BackendFigure backend = backend(snapshot, id, metric, window.from(), window.to(), percentOnly)
                .orElse(null);
        PlatformReadFigure platform = read == null ? null : platformFigure(snapshot, id, metric, read, percentOnly);

        Boolean connectorVsBackend = backend == null ? null : backend.matches();
        Boolean connectorVsRead = platform == null ? null : platform.matches();
        Boolean backendVsRead = null;
        if (platform != null && platform.matches() != null && backend != null) {
            backendVsRead = backendOver(snapshot, id, metric, read.getDayFrom(), read.getDayTo())
                    .map(b -> ReconciliationBand.matches(b, platform.value(), percentOnly)).orElse(null);
        }
        Verdict verdict = decide(connectorVsBackend, connectorVsRead, backendVsRead);
        boolean mismatch = Boolean.FALSE.equals(connectorVsBackend) || Boolean.FALSE.equals(connectorVsRead);
        boolean stale = read != null && read.getRecordedAt().isBefore(now.minusDays(STALE_READ_DAYS));
        String note = trustNote(verdict, shown.value(), backend, platform, percentOnly, stale, now);
        return new LedgerRow(id.name(), metric.key(), id.name() + "." + metric.key(), metric.label(), metric.unit(),
                metric.definition(), window.from(), window.to(), shown.value(), backend, platform, verdict, mismatch,
                stale, note);
    }

    /**
     * The verdict from what each second source says about the connector: {@code connectorVsBackend} and
     * {@code connectorVsRead} are true (agree), false (disagree) or null (that source is absent or cannot be
     * compared); {@code backendVsRead} matters only when both disagree with the connector.
     */
    static Verdict decide(Boolean connectorVsBackend, Boolean connectorVsRead, Boolean backendVsRead) {
        if (connectorVsBackend == null && connectorVsRead == null) {
            return Verdict.UNCHECKED;
        }
        boolean backendAgrees = !Boolean.FALSE.equals(connectorVsBackend);
        boolean readAgrees = !Boolean.FALSE.equals(connectorVsRead);
        if (backendAgrees && readAgrees) {
            return Verdict.BOTH;
        }
        if (connectorVsRead == null) {
            return Verdict.CONNECTOR; // only the backend could be compared, and it disagrees
        }
        if (connectorVsBackend == null) {
            return Verdict.PLATFORM; // only the read could be compared, and it disagrees
        }
        if (!backendAgrees && readAgrees) {
            return Verdict.CONNECTOR;
        }
        if (backendAgrees) {
            return Verdict.PLATFORM; // the read disagrees with the connector and the backend that agrees with it
        }
        return Boolean.TRUE.equals(backendVsRead) ? Verdict.BACKEND : Verdict.PLATFORM;
    }

    private Optional<BackendFigure> backend(Snapshot snapshot, MetricChannel channel, Metric metric, LocalDate from,
                                            LocalDate to, boolean percentOnly) {
        Optional<ReferencePairs.Pair> pair = ReferencePairs.find(channel, metric.key());
        if (pair.isEmpty() || to == null) {
            return Optional.empty();
        }
        Map<LocalDate, Double> own = snapshot.dailyValues(channel, metric.stored(), "");
        Map<LocalDate, Double> reference = snapshot.dailyValues(MetricChannel.PRODUCT, pair.get().reference(), "");
        return ReferencePairs.compare(own, reference, from, to).map(c -> new BackendFigure(c.referenceSum(),
                c.ownSum(), c.days(), backendSource(snapshot), c.first(), c.last(),
                ReconciliationBand.matches(c.ownSum(), c.referenceSum(), percentOnly)));
    }

    /** The backend's figure over an explicit range: its settled days inside it; empty when it has none or no pair exists. */
    private Optional<Double> backendOver(Snapshot snapshot, MetricChannel channel, Metric metric, LocalDate from,
                                         LocalDate to) {
        Optional<ReferencePairs.Pair> pair = ReferencePairs.find(channel, metric.key());
        if (pair.isEmpty()) {
            return Optional.empty();
        }
        double sum = 0;
        boolean any = false;
        for (Map.Entry<LocalDate, Double> e : snapshot.dailyValues(MetricChannel.PRODUCT, pair.get().reference(), "")
                .entrySet()) {
            if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) {
                sum += e.getValue();
                any = true;
            }
        }
        return any ? Optional.of(sum) : Optional.empty();
    }

    private static String backendSource(Snapshot snapshot) {
        SnapshotChannel product = snapshot.channels().get(MetricChannel.PRODUCT);
        if (product == null) {
            return "Product metrics";
        }
        String names = product.view().connections().stream().map(MarketingOverviewQueryService.ConnectionView::name)
                .distinct().reduce((a, b) -> a + ", " + b).orElse("");
        return names.isEmpty() ? "Product metrics" : names;
    }

    private PlatformReadFigure platformFigure(Snapshot snapshot, MetricChannel channel, Metric metric,
                                              MarketingPlatformRead read, boolean percentOnly) {
        Double conductor = snapshot.valueOver(channel, metric.key(), read.getDayFrom(), read.getDayTo());
        double value = read.getValue().doubleValue();
        Boolean matches = conductor == null ? null : ReconciliationBand.matches(conductor, value, percentOnly);
        return new PlatformReadFigure(read.getId(), value, read.getDayFrom(), read.getDayTo(), read.getSourceName(),
                read.getNote(), read.getRecordedBy(), read.getRecordedAt(), conductor, matches);
    }

    private static Summary summarize(List<LedgerRow> rows) {
        int[] byVerdict = new int[Verdict.values().length];
        int mismatched = 0;
        int stale = 0;
        for (LedgerRow row : rows) {
            byVerdict[row.verdict().ordinal()]++;
            mismatched += row.mismatch() ? 1 : 0;
            stale += row.readStale() ? 1 : 0;
        }
        return new Summary(byVerdict[Verdict.BOTH.ordinal()], byVerdict[Verdict.CONNECTOR.ordinal()],
                byVerdict[Verdict.BACKEND.ordinal()], byVerdict[Verdict.PLATFORM.ordinal()],
                byVerdict[Verdict.UNCHECKED.ordinal()], mismatched, stale);
    }

    // ---- wording ------------------------------------------------------------------------------------

    private static String trustNote(Verdict verdict, Double conductor, BackendFigure backend,
                                    PlatformReadFigure read, boolean percentOnly, boolean stale, OffsetDateTime now) {
        String text = switch (verdict) {
            case UNCHECKED -> read != null
                    ? "A read of " + days(read) + " is logged, but Conductor has no settled figure for those days to"
                            + " check it against, so no second source has confirmed this number yet."
                    : "Only Conductor's connector reports this; no second source has checked it. Log a direct"
                            + " platform read to check it.";
            case BOTH -> "Conductor agrees with " + agreeing(backend, read, percentOnly) + ".";
            case CONNECTOR -> read != null && Boolean.TRUE.equals(read.matches())
                    ? "Trust the connector: the " + read.sourceName() + " read agrees with it ("
                            + ReconciliationBand.figure(read.value(), percentOnly) + " for " + days(read)
                            + "), but the product backend differs ("
                            + gap(backend, percentOnly) + ")."
                    : "The product backend differs from the connector (" + gap(backend, percentOnly) + ") and no"
                            + " direct read has checked it; trust the connector until the gap is explained.";
            case BACKEND -> "Trust the product backend: it agrees with the " + read.sourceName() + " read ("
                    + ReconciliationBand.figure(read.value(), percentOnly) + " for " + days(read)
                    + "), while the connector reports "
                    + ReconciliationBand.figure(read.conductorValueOverReadDays(), percentOnly) + ".";
            case PLATFORM -> "Trust the platform read: " + read.sourceName() + " shows "
                    + ReconciliationBand.figure(read.value(), percentOnly) + " for " + days(read)
                    + ", Conductor reports " + ReconciliationBand.figure(read.conductorValueOverReadDays(), percentOnly)
                    + (backend != null && !backend.matches()
                            ? " and the product backend " + ReconciliationBand.figure(backend.value(), percentOnly)
                            : "")
                    + ".";
        };
        if (stale && read != null) {
            long age = ChronoUnit.DAYS.between(read.recordedAt(), now);
            text += " The read is " + age + " days old; log a new one.";
        }
        return text;
    }

    private static String agreeing(BackendFigure backend, PlatformReadFigure read, boolean percentOnly) {
        List<String> parts = new ArrayList<>();
        if (backend != null && backend.matches()) {
            parts.add("the product backend (" + ReconciliationBand.figure(backend.value(), percentOnly) + " against "
                    + ReconciliationBand.figure(backend.conductorValue(), percentOnly) + " over " + backend.daysCompared()
                    + " settled " + (backend.daysCompared() == 1 ? "day" : "days") + ")");
        }
        if (read != null && Boolean.TRUE.equals(read.matches())) {
            parts.add("the " + read.sourceName() + " read (" + ReconciliationBand.figure(read.value(), percentOnly)
                    + " for " + days(read) + ")");
        }
        return String.join(" and ", parts);
    }

    private static String gap(BackendFigure backend, boolean percentOnly) {
        return "Conductor " + ReconciliationBand.figure(backend.conductorValue(), percentOnly) + " vs backend "
                + ReconciliationBand.figure(backend.value(), percentOnly) + " over " + backend.daysCompared()
                + (backend.daysCompared() == 1 ? " settled day" : " settled days");
    }

    private static String days(PlatformReadFigure read) {
        return read.from().equals(read.to()) ? read.from().toString() : read.from() + ".." + read.to();
    }

    // ---- mapping ------------------------------------------------------------------------------------

    private static ReadEntry toEntry(MarketingPlatformRead r) {
        return new ReadEntry(r.getId(), r.getChannel(), r.getMetric(), r.getDayFrom(), r.getDayTo(),
                r.getValue().doubleValue(), r.getSourceName(), r.getNote(), r.getRecordedBy(), r.getRecordedAt(),
                r.getConductorValue() == null ? null : r.getConductorValue().doubleValue(),
                r.getBackendValue() == null ? null : r.getBackendValue().doubleValue(), r.getVerdict(),
                r.getRetractedAt(), r.getRetractedBy(), r.getRetractionReason());
    }

    private static String normalizeRef(String metricRef) {
        if (metricRef == null) {
            return "";
        }
        String trimmed = metricRef.trim();
        int dot = trimmed.indexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(0, dot).toUpperCase(Locale.ROOT) + trimmed.substring(dot);
    }
}
