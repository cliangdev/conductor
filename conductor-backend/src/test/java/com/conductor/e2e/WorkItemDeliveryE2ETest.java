package com.conductor.e2e;

import com.conductor.entity.WorkItem;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forecast, health and the delivery overview against real Postgres: a PRD with twelve tasks, eight finished
 * with timestamps spread over four weeks (set directly, since the API stamps "now"), then a throughput
 * forecast; a health draft that is not current until a person confirms it; and the PRD in the overview.
 */
class WorkItemDeliveryE2ETest extends AbstractE2ETest {

    @Autowired private WorkItemRepository workItemRepository;

    private HttpHeaders auth;
    private String projectId;

    @BeforeEach
    void loginAndCreateProject() {
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "e2e-delivery@example.com", "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        auth = new HttpHeaders();
        auth.setBearerAuth((String) login.getBody().get("accessToken"));
        auth.setContentType(MediaType.APPLICATION_JSON);

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Delivery E2E", "description", "test"), auth), Map.class);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) project.getBody().get("id");
    }

    private String v2() {
        return url("/api/v2/projects/" + projectId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(HttpMethod method, String url, Object body, HttpStatus expected) {
        ResponseEntity<Map> response = rest.exchange(url, method, new HttpEntity<>(body, auth), Map.class);
        assertThat(response.getStatusCode()).as("%s %s", method, url).isEqualTo(expected);
        return response.getBody();
    }

    private String create(Map<String, Object> body) {
        return (String) call(HttpMethod.POST, v2() + "/work-items", body, HttpStatus.CREATED).get("id");
    }

    private Map<String, Object> item(String id) {
        return call(HttpMethod.GET, v2() + "/work-items/" + id, null, HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void aPrdWithEightFinishedTasksForecastsHasDraftHealthThatNeedsConfirmingAndAppearsInTheOverview() {
        String prd = create(Map.of("title", "Big feature", "type", "PRD", "workflow", "ENGINEERING",
                "appetite", "4w"));
        // A second PRD with no plan: nothing to forecast.
        String bare = create(Map.of("title", "Bare", "type", "PRD", "workflow", "ENGINEERING"));
        assertThat(call(HttpMethod.GET, v2() + "/work-items/" + bare + "/forecast", null, HttpStatus.OK)
                .get("state")).isEqualTo("NO_PLAN");

        List<String> tasks = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            tasks.add(create(Map.of("title", "Task " + i, "parentWorkItemId", prd)));
        }
        // Finish eight, then back-date them over four weeks.
        int[] daysAgo = {27, 26, 25, 20, 19, 13, 12, 11};
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < 8; i++) {
            String id = tasks.get(i);
            call(HttpMethod.PATCH, v2() + "/work-items/" + id, Map.of("status", "IN_PROGRESS"), HttpStatus.OK);
            call(HttpMethod.PATCH, v2() + "/work-items/" + id, Map.of("status", "DONE"), HttpStatus.OK);
            WorkItem w = workItemRepository.findById(id).orElseThrow();
            w.setCompletedAt(now.minusDays(daysAgo[i]));
            w.setStartedAt(now.minusDays(daysAgo[i] + 2L));
            workItemRepository.save(w);
        }
        WorkItem root = workItemRepository.findById(prd).orElseThrow();
        root.setStartedAt(now.minusDays(10));
        workItemRepository.save(root);

        // --- forecast ---
        var forecast = call(HttpMethod.GET, v2() + "/work-items/" + prd + "/forecast", null, HttpStatus.OK);
        assertThat(forecast.get("state")).isEqualTo("OK");
        assertThat(forecast.get("mode")).isEqualTo("THROUGHPUT");
        assertThat(forecast.get("remaining")).isEqualTo(4);
        assertThat(forecast.get("samples")).isEqualTo(8);
        LocalDate p50 = LocalDate.parse((String) forecast.get("p50Date"));
        LocalDate p85 = LocalDate.parse((String) forecast.get("p85Date"));
        assertThat(p50).isAfter(LocalDate.now().minusDays(1));
        assertThat(p85).isAfterOrEqualTo(p50);
        assertThat(LocalDate.parse((String) forecast.get("appetiteEndsOn")))
                .isEqualTo(root.getStartedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate()
                        .plusDays(28));
        assertThat(forecast.get("appetiteFit")).isIn("FITS", "TIGHT", "EXCEEDS");

        // --- health: a draft is not current until a person confirms it ---
        var draft = call(HttpMethod.POST, v2() + "/work-items/" + prd + "/health-updates",
                Map.of("health", "AT_RISK", "note", "Two tasks blocked on infra", "draft", true), HttpStatus.CREATED);
        assertThat(draft.get("confirmed")).isEqualTo(false);
        String draftId = (String) draft.get("id");

        var withDraft = map(item(prd).get("health"));
        assertThat(withDraft.get("health")).isNull();
        assertThat(map(withDraft.get("pendingDraft")).get("updateId")).isEqualTo(draftId);
        assertThat(map(withDraft.get("pendingDraft")).get("health")).isEqualTo("AT_RISK");

        var confirmed = call(HttpMethod.POST, v2() + "/work-items/" + prd + "/health-updates/" + draftId + "/confirm",
                null, HttpStatus.OK);
        assertThat(confirmed.get("confirmed")).isEqualTo(true);
        assertThat(confirmed.get("confirmedByName")).isNotNull();

        var current = map(item(prd).get("health"));
        assertThat(current.get("health")).isEqualTo("AT_RISK");
        assertThat(current.get("note")).isEqualTo("Two tasks blocked on infra");
        assertThat(current.get("pendingDraft")).isNull();
        assertThat(current.get("atRisk")).isEqualTo(true);
        assertThat(current.get("stale")).isEqualTo(false);

        // A newer draft is pending while the confirmed update stays current; confirming it again is a 409.
        call(HttpMethod.POST, v2() + "/work-items/" + prd + "/health-updates",
                Map.of("health", "ON_TRACK", "note", "Infra unblocked", "draft", true), HttpStatus.CREATED);
        var again = map(item(prd).get("health"));
        assertThat(again.get("health")).isEqualTo("AT_RISK");
        assertThat(map(again.get("pendingDraft")).get("health")).isEqualTo("ON_TRACK");
        call(HttpMethod.POST, v2() + "/work-items/" + prd + "/health-updates/" + draftId + "/confirm", null,
                HttpStatus.CONFLICT);

        // A person recording directly confirms in the same write and supersedes the draft.
        call(HttpMethod.POST, v2() + "/work-items/" + prd + "/health-updates",
                Map.of("health", "ON_TRACK", "note", "Back on track", "draft", false), HttpStatus.CREATED);
        var recovered = map(item(prd).get("health"));
        assertThat(recovered.get("health")).isEqualTo("ON_TRACK");
        assertThat(recovered.get("pendingDraft")).isNull();
        @SuppressWarnings("unchecked")
        List<Object> history = rest.exchange(v2() + "/work-items/" + prd + "/health-updates", HttpMethod.GET,
                new HttpEntity<>(auth), List.class).getBody();
        assertThat(history).hasSize(3);

        // The tree carries the same health on the PRD node.
        var tree = call(HttpMethod.GET, v2() + "/work-items/" + prd + "/tree", null, HttpStatus.OK);
        assertThat(map(tree.get("health")).get("health")).isEqualTo("ON_TRACK");

        // --- overview ---
        var overview = call(HttpMethod.GET, v2() + "/delivery-overview", null, HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> active = (List<Map<String, Object>>) overview.get("activeItems");
        assertThat(active).extracting(a -> map(a.get("item")).get("id")).contains(prd, bare);
        Map<String, Object> entry = active.stream()
                .filter(a -> prd.equals(map(a.get("item")).get("id"))).findFirst().orElseThrow();
        assertThat(map(entry.get("progress")).get("total")).isEqualTo(12);
        assertThat(map(entry.get("progress")).get("done")).isEqualTo(8);
        assertThat(map(entry.get("forecast")).get("state")).isEqualTo("OK");
        assertThat(map(entry.get("health")).get("health")).isEqualTo("ON_TRACK");
        assertThat(map(entry.get("signals"))).containsKey("blocked");
        assertThat(map(overview.get("attention"))).containsKeys("blocked", "stale", "needsInput");
        assertThat(overview.get("milestones")).isNotNull();
    }
}
