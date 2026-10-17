package com.conductor.integration.connector.appstoreconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Reads Apple's App Store Connect Analytics Reports API for one app: the app's ONGOING report request, the
 * two "Standard" reports, their DAILY instances and the TSV segments of each.
 *
 * <p>Apple publishes each report day some 1-2 days late, as one instance per processing run holding one or more
 * segments. Rules this client keeps:
 * <ul>
 *   <li>Report names are an exact match. Without the "Standard" suffix Apple answers with an empty list.</li>
 *   <li>Instance lists are paged; {@code links.next} is followed.</li>
 *   <li>Segment URLs are pre-signed: they are fetched with no {@code Authorization} header, as raw bytes
 *       (gzip or zip, sniffed from the first bytes, never trusted from a header).</li>
 *   <li>Columns are found by header name, never by position; a missing required column fails the pull loudly.</li>
 *   <li>Rows of every segment of an instance are summed. When several instances carry the same report Date,
 *       the one with the latest {@code processingDate} replaces the others.</li>
 * </ul>
 */
public class AppStoreAnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(AppStoreAnalyticsClient.class);

    static final String API_BASE = "https://api.appstoreconnect.apple.com";
    static final String DISCOVERY_REPORT = "App Store Discovery and Engagement Standard";
    static final String DOWNLOADS_REPORT = "App Downloads Standard";

    static final String M_IMPRESSIONS = "impressions";
    static final String M_IMPRESSIONS_UNIQUE = "impressions_unique";
    static final String M_PAGE_VIEWS = "page_views";
    static final String M_PAGE_VIEWS_UNIQUE = "page_views_unique";
    static final String M_DOWNLOADS = "downloads";
    static final String M_REDOWNLOADS = "redownloads";

    private static final List<String> DISCOVERY_METRICS =
            List.of(M_IMPRESSIONS, M_IMPRESSIONS_UNIQUE, M_PAGE_VIEWS, M_PAGE_VIEWS_UNIQUE);
    private static final List<String> DOWNLOADS_METRICS = List.of(M_DOWNLOADS, M_REDOWNLOADS);
    private static final List<String> DISCOVERY_COLUMNS = List.of("Date", "Event", "Source Type", "Counts", "Unique Counts");
    private static final List<String> DOWNLOADS_COLUMNS = List.of("Date", "Download Type", "Source Type", "Counts");
    private static final Set<String> KNOWN_SOURCES = Set.of("APP_STORE_SEARCH", "APP_STORE_BROWSE", "APP_REFERRER",
            "WEB_REFERRER", "APP_CLIP", "INSTITUTIONAL_PURCHASE", "UNAVAILABLE");

    /**
     * source type -> metric -> value, for one report day, plus the metrics Apple's processed reports cover for
     * that day ({@code coveredMetrics}). A covered metric with no rows is a real zero: Apple's TSVs omit
     * zero rows, so the connector stores it as 0 instead of leaving a gap.
     */
    public record DayRows(Map<String, Map<String, Double>> bySource, Set<String> coveredMetrics) {}

    /** The failure to read or understand Apple's reports (as opposed to an HTTP status failure). */
    public static class ReportException extends RuntimeException {
        public ReportException(String message) {
            super(message);
        }
    }

    private record Instance(String id, LocalDate processingDate) {}

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppStoreAnalyticsClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // -- app + report request ------------------------------------------------------------------------

    /** {@code GET /v1/apps/{id}}; throws {@link HttpClientErrorException} for 401/403/404. */
    public void getApp(String token, String appId) {
        get(token, URI.create(API_BASE + "/v1/apps/" + encode(appId)));
    }

    public Optional<String> findOngoingRequest(String token, String appId) {
        URI uri = URI.create(API_BASE + "/v1/apps/" + encode(appId) + "/analyticsReportRequests?"
                + param("filter[accessType]", "ONGOING"));
        return firstId(get(token, uri));
    }

    /** The app's ONGOING request, created when there is none (a 409 on create means it appeared meanwhile). */
    public String findOrCreateOngoingRequest(String token, String appId) {
        Optional<String> existing = findOngoingRequest(token, appId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Map<String, Object> body = Map.of("data", Map.of(
                "type", "analyticsReportRequests",
                "attributes", Map.of("accessType", "ONGOING"),
                "relationships", Map.of("app", Map.of("data", Map.of("type", "apps", "id", appId)))));
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String json = restTemplate.exchange(URI.create(API_BASE + "/v1/analyticsReportRequests"), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class).getBody();
            Optional<String> created = firstId(json);
            if (created.isPresent()) {
                return created.get();
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 409) {
                throw e;
            }
            log.info("App Store Connect ONGOING analytics request for app {} already exists; reusing it", appId);
        }
        return findOngoingRequest(token, appId).orElseThrow(() ->
                new ReportException("Apple did not return an ONGOING analytics report request for this app"));
    }

    // -- the pull ------------------------------------------------------------------------------------

    /**
     * Per report day, the totals and per-source-type totals of the Discovery and Downloads reports for the days
     * in {@code from..to}. A day Apple has not published has no entry. Metrics a day's report does not carry are
     * simply absent from its rows.
     */
    public Map<LocalDate, DayRows> fetchDays(String token, String appId, LocalDate from, LocalDate to) {
        String requestId = findOrCreateOngoingRequest(token, appId);
        Map<LocalDate, Map<String, Map<String, Double>>> merged = new TreeMap<>();
        Map<LocalDate, Set<String>> covered = new TreeMap<>();
        collect(token, requestId, DISCOVERY_REPORT, DISCOVERY_COLUMNS, DISCOVERY_METRICS, from, to, merged, covered);
        collect(token, requestId, DOWNLOADS_REPORT, DOWNLOADS_COLUMNS, DOWNLOADS_METRICS, from, to, merged, covered);
        Map<LocalDate, DayRows> out = new TreeMap<>();
        Set<LocalDate> days = new java.util.TreeSet<>(merged.keySet());
        days.addAll(covered.keySet());
        for (LocalDate day : days) {
            out.put(day, new DayRows(merged.getOrDefault(day, Map.of()), covered.getOrDefault(day, Set.of())));
        }
        return out;
    }

    private void collect(String token, String requestId, String reportName, List<String> required,
                         List<String> reportMetrics, LocalDate from, LocalDate to,
                         Map<LocalDate, Map<String, Map<String, Double>>> merged,
                         Map<LocalDate, Set<String>> covered) {
        Optional<String> reportId = findReportId(token, requestId, reportName);
        if (reportId.isEmpty()) {
            log.warn("App Store Connect report '{}' not found under request {}; no data yet", reportName, requestId);
            return;
        }
        // report Date -> rows of the instance (so far) with the latest processing date carrying it
        Map<LocalDate, Map<String, Map<String, Double>>> winners = new HashMap<>();
        List<Instance> instances = listInstances(token, reportId.get(), from);
        instances.sort(Comparator.comparing(Instance::processingDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Instance::id));
        for (Instance instance : instances) {
            // An instance processed on day P carries report day P-1 (and restates P-2). Apple omits zero rows, so
            // P-1 is covered even when the segments have no row for it: that is a real zero, not a missing day.
            if (instance.processingDate() != null) {
                LocalDate reportDay = instance.processingDate().minusDays(1);
                if (!reportDay.isBefore(from) && !reportDay.isAfter(to)) {
                    covered.computeIfAbsent(reportDay, d -> new java.util.HashSet<>()).addAll(reportMetrics);
                }
            }
            Map<LocalDate, Map<String, Map<String, Double>>> perInstance = new HashMap<>();
            for (byte[] segment : segments(token, instance.id())) {
                parseSegment(reportName, required, text(segment), perInstance);
            }
            winners.putAll(perInstance); // later processing date replaces the whole day
        }
        winners.forEach((day, rows) -> {
            if (day.isBefore(from) || day.isAfter(to)) {
                return;
            }
            Map<String, Map<String, Double>> target = merged.computeIfAbsent(day, d -> new HashMap<>());
            rows.forEach((source, metrics) ->
                    metrics.forEach((m, v) -> target.computeIfAbsent(source, s -> new HashMap<>()).merge(m, v, Double::sum)));
        });
    }

    Optional<String> findReportId(String token, String requestId, String name) {
        URI uri = URI.create(API_BASE + "/v1/analyticsReportRequests/" + encode(requestId) + "/reports?"
                + param("filter[name]", name));
        return firstId(get(token, uri));
    }

    private List<Instance> listInstances(String token, String reportId, LocalDate processedSince) {
        List<Instance> out = new ArrayList<>();
        URI next = URI.create(API_BASE + "/v1/analyticsReports/" + encode(reportId) + "/instances?"
                + param("filter[granularity]", "DAILY") + "&limit=200");
        int pages = 0;
        while (next != null && pages++ < 50) {
            JsonNode root = readTree(get(token, next));
            for (JsonNode node : root.path("data")) {
                String id = text(node.path("id"));
                if (id == null) continue;
                LocalDate processed = date(text(node.path("attributes").path("processingDate")));
                if (processed != null && processed.isBefore(processedSince)) continue;
                out.add(new Instance(id, processed));
            }
            String link = text(root.path("links").path("next"));
            next = link == null ? null : URI.create(link);
        }
        return out;
    }

    private List<byte[]> segments(String token, String instanceId) {
        JsonNode root = readTree(get(token, URI.create(API_BASE + "/v1/analyticsReportInstances/" + encode(instanceId)
                + "/segments")));
        List<byte[]> out = new ArrayList<>();
        for (JsonNode seg : root.path("data")) {
            String url = text(seg.path("attributes").path("url"));
            if (url == null) continue;
            // Pre-signed storage URL: no Authorization header.
            byte[] bytes = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()),
                    byte[].class).getBody();
            if (bytes != null) out.add(bytes);
        }
        return out;
    }

    // -- TSV -----------------------------------------------------------------------------------------

    private void parseSegment(String reportName, List<String> required, String text,
                              Map<LocalDate, Map<String, Map<String, Double>>> into) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            return;
        }
        Map<String, Integer> index = new HashMap<>();
        String[] header = lines[0].replace("\r", "").split("\t", -1);
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim(), i);
        }
        List<String> missing = required.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new ReportException("Apple's '" + reportName + "' report is missing the column(s) " + missing
                    + " (found " + index.keySet() + ")");
        }
        boolean discovery = DISCOVERY_REPORT.equals(reportName);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = lines[i].replace("\r", "").split("\t", -1);
            LocalDate day = date(field(f, index, "Date"));
            if (day == null) continue;
            String source = sourceType(field(f, index, "Source Type"));
            double counts = number(field(f, index, "Counts"));
            Map<String, Double> metrics = into.computeIfAbsent(day, d -> new HashMap<>())
                    .computeIfAbsent(source, s -> new HashMap<>());
            if (discovery) {
                String event = field(f, index, "Event");
                double unique = number(field(f, index, "Unique Counts"));
                if ("Impression".equalsIgnoreCase(event)) {
                    metrics.merge(M_IMPRESSIONS, counts, Double::sum);
                    metrics.merge(M_IMPRESSIONS_UNIQUE, unique, Double::sum);
                } else if ("Page View".equalsIgnoreCase(event)) {
                    metrics.merge(M_PAGE_VIEWS, counts, Double::sum);
                    metrics.merge(M_PAGE_VIEWS_UNIQUE, unique, Double::sum);
                }
            } else {
                String type = field(f, index, "Download Type");
                if ("First-time download".equalsIgnoreCase(type)) {
                    metrics.merge(M_DOWNLOADS, counts, Double::sum);
                } else if ("Redownload".equalsIgnoreCase(type)) {
                    metrics.merge(M_REDOWNLOADS, counts, Double::sum);
                }
            }
        }
    }

    /** Upper-case, spaces and dashes to underscores; anything outside Apple's known set becomes OTHER. */
    static String sourceType(String raw) {
        if (raw == null || raw.isBlank()) return "UNAVAILABLE";
        String n = raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s-]+", "_");
        return KNOWN_SOURCES.contains(n) ? n : "OTHER";
    }

    /** Gzip (1f 8b) or zip (50 4b) sniffed from the first two bytes; anything else is plain text. */
    static String text(byte[] bytes) {
        try {
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1f && (bytes[1] & 0xFF) == 0x8b) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4b) {
                try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    return in.getNextEntry() == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new ReportException("Could not unpack an Apple report segment: " + e.getMessage());
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // -- helpers -------------------------------------------------------------------------------------

    private String get(String token, URI uri) {
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(auth(token)), String.class).getBody();
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (IOException e) {
            throw new ReportException("Apple returned a response that is not JSON");
        }
    }

    private Optional<String> firstId(String json) {
        JsonNode data = readTree(json).path("data");
        if (data.isArray() && !data.isEmpty()) {
            return Optional.ofNullable(text(data.get(0).path("id")));
        }
        if (data.isObject()) {
            return Optional.ofNullable(text(data.path("id")));
        }
        return Optional.empty();
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private static String param(String name, String value) {
        return encode(name) + "=" + encode(value);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String field(String[] fields, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        return i == null || i >= fields.length ? "" : fields[i].trim();
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim().length() > 10 ? raw.trim().substring(0, 10) : raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
