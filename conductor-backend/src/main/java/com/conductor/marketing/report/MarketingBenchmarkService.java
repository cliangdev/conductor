package com.conductor.marketing.report;

import com.conductor.exception.BusinessException;
import com.conductor.integration.MetricChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stores the industry benchmarks a workspace compares itself with. A benchmark must say where it came from
 * ({@code sourceName}, an http(s) {@code sourceUrl}) and the day it was checked, so a figure is never recorded
 * from memory. Callers check project access; this class validates and persists.
 */
@Service
public class MarketingBenchmarkService {

    private static final Pattern METRIC_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final MarketingBenchmarkRepository repository;

    public MarketingBenchmarkService(MarketingBenchmarkRepository repository) {
        this.repository = repository;
    }

    /**
     * The fields of a benchmark a caller supplies; the metric reference comes from the path. An upsert replaces the
     * whole benchmark, so an omitted {@code numerator}, {@code denominator} or {@code sourceType} clears the stored one.
     */
    public record Input(String label, Double value, String rangeText, String segment, String sourceName,
                        String sourceUrl, LocalDate checkedOn, String notes, String numerator, String denominator,
                        String sourceType) {}

    /** A benchmark's definition after validation and normalisation; all fields null when none was supplied. */
    private record Definition(String numerator, String denominator, String sourceType) {}

    @Transactional(readOnly = true)
    public List<MarketingBenchmark> list(String projectId) {
        return repository.findByProjectIdOrderByMetricRefAsc(projectId);
    }

    @Transactional
    public MarketingBenchmark upsert(String projectId, String metricRefRaw, Input input, String actorId) {
        String metricRef = normalizeRef(metricRefRaw);
        if (input.value() == null || input.value().isNaN() || input.value().isInfinite()) {
            throw new BusinessException("Benchmark value must be a number");
        }
        if (input.sourceName() == null || input.sourceName().isBlank()) {
            throw new BusinessException("A benchmark needs the name of its source");
        }
        requireHttpUrl(input.sourceUrl());
        if (input.checkedOn() == null) {
            throw new BusinessException("A benchmark needs the date it was checked");
        }
        if (input.checkedOn().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new BusinessException("checkedOn cannot be in the future");
        }
        Definition definition = validateDefinition(input);
        MarketingBenchmark row = repository.findByProjectIdAndMetricRef(projectId, metricRef).orElseGet(() -> {
            MarketingBenchmark created = new MarketingBenchmark();
            created.setProjectId(projectId);
            created.setMetricRef(metricRef);
            created.setCreatedBy(actorId);
            return created;
        });
        row.setLabel(blankToNull(input.label()));
        row.setValue(BigDecimal.valueOf(input.value()));
        row.setRangeText(blankToNull(input.rangeText()));
        row.setSegment(blankToNull(input.segment()));
        row.setSourceName(input.sourceName().trim());
        row.setSourceUrl(input.sourceUrl().trim());
        row.setCheckedOn(input.checkedOn());
        row.setNotes(blankToNull(input.notes()));
        row.setNumerator(definition.numerator());
        row.setDenominator(definition.denominator());
        row.setSourceType(definition.sourceType());
        row.setUpdatedAt(OffsetDateTime.now());
        return repository.save(row);
    }

    @Transactional
    public void delete(String projectId, String metricRefRaw) {
        repository.deleteByProjectIdAndMetricRef(projectId, normalizeRef(metricRefRaw));
    }

    /** {@code CHANNEL.metric} with a known channel; the channel part is upper-cased. */
    static String normalizeRef(String raw) {
        String ref = raw == null ? "" : raw.trim();
        int dot = ref.indexOf('.');
        if (dot <= 0 || dot == ref.length() - 1) {
            throw new BusinessException("metricRef must look like CHANNEL.metric, e.g. STORE.conversion_rate");
        }
        String channel = ref.substring(0, dot).toUpperCase(Locale.ROOT);
        String metric = ref.substring(dot + 1);
        try {
            MetricChannel.valueOf(channel);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown channel '" + ref.substring(0, dot) + "' in metricRef; expected one of "
                    + List.of(MetricChannel.values()));
        }
        if (!METRIC_KEY.matcher(metric).matches()) {
            throw new BusinessException("metricRef has an invalid metric key '" + metric + "'");
        }
        return channel + "." + metric;
    }

    /** A denominator or source type means nothing without a numerator, so a numerator is required with either. */
    private static Definition validateDefinition(Input input) {
        String numerator = BenchmarkComparability.measure(input.numerator());
        String denominator = BenchmarkComparability.measure(input.denominator());
        String sourceType = BenchmarkComparability.sourceTypeOrNull(input.sourceType());
        if (numerator == null && (denominator != null || sourceType != null)) {
            throw new BusinessException("A benchmark definition needs a numerator when a denominator or sourceType is "
                    + "given; numerator is required. Allowed measures: " + allowedMeasures());
        }
        if (numerator != null && !BenchmarkComparability.isMeasure(numerator)) {
            throw new BusinessException("Unknown numerator '" + input.numerator().trim() + "'. Allowed: " + allowedMeasures());
        }
        if (denominator != null && !BenchmarkComparability.isMeasure(denominator)) {
            throw new BusinessException("Unknown denominator '" + input.denominator().trim() + "'. Allowed: "
                    + allowedMeasures());
        }
        if (sourceType != null && !BenchmarkComparability.SOURCE_TYPES.contains(sourceType)) {
            throw new BusinessException("Unknown sourceType '" + input.sourceType().trim() + "'. Allowed: "
                    + String.join(", ", BenchmarkComparability.SOURCE_TYPES));
        }
        return new Definition(numerator, denominator, sourceType);
    }

    private static String allowedMeasures() {
        return String.join(", ", BenchmarkComparability.MEASURES) + ", or product:<key> (lower-case letters, digits and "
                + "underscores, starting with a letter, up to 64 characters)";
    }

    private static void requireHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("A benchmark needs the URL of its source");
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new BusinessException("sourceUrl must be an http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessException("sourceUrl must be an http(s) URL");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
