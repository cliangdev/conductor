package com.conductor.integration.connector.appstoreconnect;

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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads Apple's App Store Connect Sales Reports API: one DAILY / SALES / SUMMARY report per vendor and day, a gzip
 * TSV of every product the vendor sold that day. This is Apple's number of record for first-time downloads, as
 * opposed to the Analytics reports ({@link AppStoreAnalyticsClient}), which split downloads by source type.
 *
 * <p>Rules this client keeps (the parser and the product-type mapping follow Rexipe's proven Sales Reports reader):
 * <ul>
 *   <li>Rows are filtered to one app by the {@code Apple Identifier} column; the report holds every app of the
 *       vendor.</li>
 *   <li>Columns are found by header name, never by position; a missing required column, or a {@code Units} value
 *       that is not a number, fails loudly. A silently wrong count is worse than no count.</li>
 *   <li>A 404 means Apple has no report for that day (not published yet, or no sales at all): the day was not read
 *       and the result is empty. It is never turned into a zero. A report that exists but has no row of ours is a
 *       read day with zero first-time downloads.</li>
 *   <li>Every other HTTP failure is left to the caller.</li>
 * </ul>
 */
public class AppStoreSalesClient {

    static final String SALES_REPORTS_URL = AppStoreAnalyticsClient.API_BASE + "/v1/salesReports";
    private static final MediaType GZIP_TSV = MediaType.parseMediaType("application/a-gzip");
    /** Rexipe pins the report version it parses; an unpinned request would follow whatever Apple makes the latest. */
    private static final String REPORT_VERSION = "1_1";

    private static final String COL_APPLE_IDENTIFIER = "Apple Identifier";
    private static final String COL_PRODUCT_TYPE = "Product Type Identifier";
    private static final String COL_UNITS = "Units";
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_APPLE_IDENTIFIER, COL_PRODUCT_TYPE, COL_UNITS);

    /** Apple's {@code Product Type Identifier} translated the way Rexipe does; anything unknown is {@link #OTHER}. */
    public enum ProductType {
        /** 1, 1F, 1T: a first-time download. */
        FIRST_TIME_DOWNLOAD,
        /** 3, 3F, 3T: an existing owner reinstalling. */
        REDOWNLOAD,
        /** 7, 7F, 7T: an app update. */
        UPDATE,
        /** IAY: a subscription purchase or renewal. */
        SUBSCRIPTION,
        /** Any other IA* code: a non-subscription in-app purchase. */
        IN_APP_PURCHASE,
        OTHER;

        static ProductType fromRaw(String raw) {
            if (raw == null || raw.isBlank()) {
                return OTHER;
            }
            String id = raw.strip().toUpperCase(Locale.ROOT);
            return switch (id) {
                case "1", "1F", "1T" -> FIRST_TIME_DOWNLOAD;
                case "3", "3F", "3T" -> REDOWNLOAD;
                case "7", "7F", "7T" -> UPDATE;
                case "IAY" -> SUBSCRIPTION;
                default -> id.startsWith("IA") ? IN_APP_PURCHASE : OTHER;
            };
        }
    }

    /** The units of one app on one read day, by product type. */
    public record SalesDay(Map<ProductType, Long> unitsByType) {
        public long firstTimeDownloads() {
            return unitsByType.getOrDefault(ProductType.FIRST_TIME_DOWNLOAD, 0L);
        }
    }

    /** The failure to understand a Sales report (as opposed to an HTTP status failure). */
    public static class SalesReportException extends RuntimeException {
        public SalesReportException(String message) {
            super(message);
        }
    }

    private final RestTemplate restTemplate;

    public AppStoreSalesClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * The app's units for {@code day}, or empty when the day was not read (404, no body, or a file with no content
     * at all). {@code vendorNumber} must already be digits only.
     */
    public Optional<SalesDay> fetchDay(String token, String vendorNumber, String appId, LocalDate day) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(GZIP_TSV));
        byte[] body;
        try {
            body = restTemplate.exchange(uri(vendorNumber, day), HttpMethod.GET, new HttpEntity<>(headers),
                    byte[].class).getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        if (body == null || body.length == 0) {
            return Optional.empty();
        }
        String tsv = text(body);
        return tsv.isBlank() ? Optional.empty() : Optional.of(parse(tsv, appId));
    }

    static SalesDay parse(String tsv, String appId) {
        String[] lines = tsv.split("\r\n|\n");
        Map<String, Integer> index = new HashMap<>();
        String[] header = lines[0].split("\t", -1);
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim(), i);
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new SalesReportException("Apple's Sales report is missing the column(s) " + missing
                    + " (found " + index.keySet() + ")");
        }
        int appCol = index.get(COL_APPLE_IDENTIFIER);
        int typeCol = index.get(COL_PRODUCT_TYPE);
        int unitsCol = index.get(COL_UNITS);
        int width = 1 + Math.max(appCol, Math.max(typeCol, unitsCol));

        Map<ProductType, Long> units = new EnumMap<>(ProductType.class);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length < width || !appId.equals(f[appCol].trim())) continue;
            String raw = f[unitsCol].trim();
            if (raw.isEmpty()) continue;
            units.merge(ProductType.fromRaw(f[typeCol]), units(raw), Long::sum);
        }
        return new SalesDay(units);
    }

    private static long units(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new SalesReportException("Apple's Sales report has a Units value that is not a number: '" + raw + "'");
        }
    }

    private static URI uri(String vendorNumber, LocalDate day) {
        return URI.create(SALES_REPORTS_URL
                + "?" + param("filter[frequency]", "DAILY")
                + "&" + param("filter[reportType]", "SALES")
                + "&" + param("filter[reportSubType]", "SUMMARY")
                + "&" + param("filter[vendorNumber]", vendorNumber)
                + "&" + param("filter[reportDate]", day.toString())
                + "&" + param("filter[version]", REPORT_VERSION));
    }

    /** Gzip when the first two bytes say so (Apple always sends gzip); anything else is read as plain text. */
    private static String text(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1f && (bytes[1] & 0xFF) == 0x8b) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new SalesReportException("Could not unpack Apple's Sales report: " + e.getMessage());
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String param(String name, String value) {
        return encode(name) + "=" + encode(value);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
