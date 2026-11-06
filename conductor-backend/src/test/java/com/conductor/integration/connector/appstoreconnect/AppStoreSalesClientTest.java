package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.connector.appstoreconnect.AppStoreSalesClient.ProductType;
import com.conductor.integration.connector.appstoreconnect.AppStoreSalesClient.SalesDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Sales Reports (daily SALES SUMMARY gzip TSV): the request, the app filter, Rexipe's product-type mapping. */
class AppStoreSalesClientTest {

    private static final String APP = "12345";
    private static final String OTHER_APP = "99999";
    private static final String VENDOR = "94514742";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final String URL = "https://api.appstoreconnect.apple.com/v1/salesReports"
            + "?filter%5Bfrequency%5D=DAILY&filter%5BreportType%5D=SALES&filter%5BreportSubType%5D=SUMMARY"
            + "&filter%5BvendorNumber%5D=" + VENDOR + "&filter%5BreportDate%5D=2026-10-01&filter%5Bversion%5D=1_1";

    private static final String HEADER = String.join("\t",
            "Provider", "Provider Country", "SKU", "Developer", "Title", "Version", "Product Type Identifier", "Units",
            "Developer Proceeds", "Begin Date", "End Date", "Customer Currency", "Country Code",
            "Currency of Proceeds", "Apple Identifier", "Customer Price", "Promo Code", "Parent Identifier",
            "Subscription", "Period", "Category", "CMB", "Device", "Supported Platforms");

    private MockRestServiceServer server;
    private AppStoreSalesClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AppStoreSalesClient(restTemplate);
    }

    private static String row(String appleId, String productType, String units) {
        String[] cols = new String[24];
        java.util.Arrays.fill(cols, "");
        cols[5] = "1.160";
        cols[6] = productType;
        cols[7] = units;
        cols[12] = "US";
        cols[14] = appleId;
        return String.join("\t", cols);
    }

    private static String tsv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + (rows.length == 0 ? "" : "\n");
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void respondWith(String text) {
        server.expect(once(), requestTo(URL)).andRespond(
                withSuccess(gzip(text), MediaType.parseMediaType("application/a-gzip")));
    }

    // -- the request ------------------------------------------------------------------------------------

    @Test
    void asksForTheVendorsDailySalesSummaryWithTheBearerTokenAndAGzipAccept() {
        server.expect(once(), requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer jwt-token"))
                .andExpect(header("Accept", "application/a-gzip"))
                .andRespond(withSuccess(gzip(tsv(row(APP, "1F", "1"))), MediaType.parseMediaType("application/a-gzip")));

        Optional<SalesDay> day = client.fetchDay("jwt-token", VENDOR, APP, DAY);

        server.verify();
        assertThat(day).isPresent();
    }

    // -- the rows ---------------------------------------------------------------------------------------

    @Test
    void countsOnlyFirstTimeDownloadUnitsOfThisAppAndIgnoresUpdatesRedownloadsAndOtherApps() {
        respondWith(tsv(
                row(APP, "1F", "20"),
                row(APP, "1", "4"),
                row(APP, "1T", "6"),
                row(APP, "7", "50"),
                row(APP, "7F", "5"),
                row(APP, "3", "3"),
                row(APP, "3F", "2"),
                row(APP, "IAY", "9"),
                row(APP, "IA1", "8"),
                row(OTHER_APP, "1F", "999"),
                row(OTHER_APP, "7", "999")));

        SalesDay day = client.fetchDay("jwt-token", VENDOR, APP, DAY).orElseThrow();

        assertThat(day.firstTimeDownloads()).isEqualTo(30);
        assertThat(day.unitsByType()).containsEntry(ProductType.FIRST_TIME_DOWNLOAD, 30L)
                .containsEntry(ProductType.UPDATE, 55L)
                .containsEntry(ProductType.REDOWNLOAD, 5L)
                .containsEntry(ProductType.SUBSCRIPTION, 9L)
                .containsEntry(ProductType.IN_APP_PURCHASE, 8L);
    }

    @Test
    void aReportWithNoRowsForThisAppIsAReadDayWithZeroFirstTimeDownloads() {
        respondWith(tsv(row(OTHER_APP, "1F", "7"), row(APP, "7", "2")));

        SalesDay day = client.fetchDay("jwt-token", VENDOR, APP, DAY).orElseThrow();

        assertThat(day.firstTimeDownloads()).isZero();
    }

    @Test
    void aHeaderOnlyReportIsAReadDayWithZeroFirstTimeDownloads() {
        respondWith(tsv());

        assertThat(client.fetchDay("jwt-token", VENDOR, APP, DAY).orElseThrow().firstTimeDownloads()).isZero();
    }

    @Test
    void anEmptyFileIsNotARecognisableReportSoTheDayIsNotRead() {
        respondWith("");

        assertThat(client.fetchDay("jwt-token", VENDOR, APP, DAY)).isEmpty();
    }

    @Test
    void columnsAreFoundByHeaderNameNotPositionAndShortOrBlankRowsAreSkipped() {
        String reordered = "Units\tApple Identifier\tProduct Type Identifier\n"
                + "5\t" + APP + "\t1F\n"
                + "\n"
                + "short\n"
                + "\t" + APP + "\t1F\n"
                + "2\t" + APP + "\t1\n";
        respondWith(reordered);

        assertThat(client.fetchDay("jwt-token", VENDOR, APP, DAY).orElseThrow().firstTimeDownloads()).isEqualTo(7);
    }

    @Test
    void aMissingRequiredColumnFailsLoudlyNamingIt() {
        respondWith("Apple Identifier\tProduct Type Identifier\n" + APP + "\t1F\n");

        assertThatThrownBy(() -> client.fetchDay("jwt-token", VENDOR, APP, DAY))
                .isInstanceOf(AppStoreSalesClient.SalesReportException.class)
                .hasMessageContaining("Units");
    }

    @Test
    void aUnitsValueThatIsNotANumberFailsLoudlyInsteadOfCountingAsZero() {
        respondWith(tsv(row(APP, "1F", "lots")));

        assertThatThrownBy(() -> client.fetchDay("jwt-token", VENDOR, APP, DAY))
                .isInstanceOf(AppStoreSalesClient.SalesReportException.class)
                .hasMessageContaining("Units");
    }

    // -- HTTP outcomes ----------------------------------------------------------------------------------

    @Test
    void aNotFoundDayIsNotReadAndIsNotAnError() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"title\":\"The request expected results but none were found\"}]}"));

        assertThat(client.fetchDay("jwt-token", VENDOR, APP, DAY)).isEmpty();
    }

    @Test
    void anOkResponseWithNoBodyIsNotRead() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess());

        assertThat(client.fetchDay("jwt-token", VENDOR, APP, DAY)).isEmpty();
    }

    @Test
    void anyOtherHttpFailureIsLeftToTheCaller() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.fetchDay("jwt-token", VENDOR, APP, DAY))
                .isInstanceOf(HttpClientErrorException.class);
    }

    // -- product type mapping (Rexipe's) ----------------------------------------------------------------

    @Test
    void productTypesFollowRexipesMapping() {
        for (String raw : new String[] {"1", "1F", "1T", " 1f "}) {
            assertThat(ProductType.fromRaw(raw)).as(raw).isEqualTo(ProductType.FIRST_TIME_DOWNLOAD);
        }
        for (String raw : new String[] {"3", "3F", "3T"}) {
            assertThat(ProductType.fromRaw(raw)).as(raw).isEqualTo(ProductType.REDOWNLOAD);
        }
        for (String raw : new String[] {"7", "7F", "7T"}) {
            assertThat(ProductType.fromRaw(raw)).as(raw).isEqualTo(ProductType.UPDATE);
        }
        assertThat(ProductType.fromRaw("IAY")).isEqualTo(ProductType.SUBSCRIPTION);
        assertThat(ProductType.fromRaw("IA1")).isEqualTo(ProductType.IN_APP_PURCHASE);
        assertThat(ProductType.fromRaw("IAC")).isEqualTo(ProductType.IN_APP_PURCHASE);
        assertThat(ProductType.fromRaw("F1")).isEqualTo(ProductType.OTHER);
        assertThat(ProductType.fromRaw("")).isEqualTo(ProductType.OTHER);
        assertThat(ProductType.fromRaw(null)).isEqualTo(ProductType.OTHER);
    }
}
