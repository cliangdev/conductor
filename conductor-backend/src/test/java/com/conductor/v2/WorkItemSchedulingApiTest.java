package com.conductor.v2;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for the generic per-item scheduling fields on the v2 Work Item surface. The service
 * and the migration are already covered by {@code WorkItemSchedulingTest}; what only a real request can
 * prove is that the controller actually unpacks {@code scheduledFor}/{@code scheduleTimezone} off the patch
 * DTO and maps them back onto the response — a field can be declared in {@code openapi-v2.yaml}, generated
 * into the DTOs, and still be silently dropped at the HTTP layer.
 */
class WorkItemSchedulingApiTest extends AbstractE2ETest {

    private HttpHeaders authHeaders;
    private String projectId;
    private String workItemId;

    @BeforeEach
    void setUp() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-wi-scheduling@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "WI Scheduling E2E", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Schedulable item", "type", "PRD", "workflow", "ENGINEERING"),
                        authHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        workItemId = (String) createResp.getBody().get("id");
    }

    // [auto] scheduledFor and scheduleTimezone round-trip over the HTTP v2 API
    @Test
    void patchThenGetRoundTripsBothSchedulingFields() {
        OffsetDateTime scheduledFor = OffsetDateTime.of(2026, 11, 3, 14, 30, 0, 0, ZoneOffset.UTC);

        var patchResp = rest.exchange(workItemUrl(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "scheduledFor", "2026-11-03T14:30:00Z",
                        "scheduleTimezone", "America/New_York"), authHeaders),
                Map.class);

        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(OffsetDateTime.parse((String) patchResp.getBody().get("scheduledFor")))
                .isEqualTo(scheduledFor);
        assertThat(patchResp.getBody().get("scheduleTimezone")).isEqualTo("America/New_York");

        var getResp = rest.exchange(workItemUrl(), HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(OffsetDateTime.parse((String) getResp.getBody().get("scheduledFor")))
                .isEqualTo(scheduledFor);
        assertThat(getResp.getBody().get("scheduleTimezone")).isEqualTo("America/New_York");
    }

    // [auto] scheduledFor and scheduleTimezone round-trip over the HTTP v2 API
    @Test
    void unscheduledWorkItemReportsBothFieldsAsNull() {
        var getResp = rest.exchange(workItemUrl(), HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);

        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("scheduledFor")).isNull();
        assertThat(getResp.getBody().get("scheduleTimezone")).isNull();
    }

    // [auto] scheduledFor and scheduleTimezone round-trip over the HTTP v2 API
    @Test
    void patchOmittingSchedulingFieldsLeavesThemUnchanged() {
        rest.exchange(workItemUrl(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "scheduledFor", "2026-12-01T09:00:00Z",
                        "scheduleTimezone", "Europe/London"), authHeaders),
                Map.class);

        var patchResp = rest.exchange(workItemUrl(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "Renamed"), authHeaders), Map.class);

        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResp.getBody().get("title")).isEqualTo("Renamed");
        assertThat(OffsetDateTime.parse((String) patchResp.getBody().get("scheduledFor")))
                .isEqualTo(OffsetDateTime.of(2026, 12, 1, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(patchResp.getBody().get("scheduleTimezone")).isEqualTo("Europe/London");
    }

    // [auto] An unknown IANA timezone returns an RFC 7807 400 over HTTP
    @Test
    void unknownTimezoneReturnsRfc7807BadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("scheduledFor", "2026-11-03T14:30:00Z");
        body.put("scheduleTimezone", "Mars/Olympus_Mons");

        var resp = rest.exchange(workItemUrl(), HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getHeaders().getContentType())
                .isNotNull()
                .satisfies(ct -> assertThat(ct.toString()).startsWith("application/problem+json"));
        assertThat(resp.getBody())
                .contains("\"status\":400")
                .contains("\"title\"")
                .contains("Mars/Olympus_Mons");
    }

    // [auto] An unknown IANA timezone returns an RFC 7807 400 over HTTP
    @Test
    void rejectedTimezonePatchPersistsNoOtherField() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Should not stick");
        body.put("scheduleTimezone", "Not/AZone");

        var patchResp = rest.exchange(workItemUrl(), HttpMethod.PATCH,
                new HttpEntity<>(body, authHeaders), String.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var getResp = rest.exchange(workItemUrl(), HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(getResp.getBody().get("title")).isEqualTo("Schedulable item");
        assertThat(getResp.getBody().get("scheduleTimezone")).isNull();
    }

    private String workItemUrl() {
        return url("/api/v2/projects/" + projectId + "/work-items/" + workItemId);
    }
}
