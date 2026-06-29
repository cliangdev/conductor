package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the v2 Work Item sub-resources — assets, step-runs, and the outcome metric —
 * against real Postgres. Mirrors {@link WorkItemV2FlowE2ETest}: log in, create a project (which seeds the
 * ENGINEERING workflow), create a Work Item via {@code POST /api/v2/.../work-items}, then exercise the
 * sub-resources. These v2 controllers delegate to the same {@code AssetService}/{@code StepRunService}/
 * {@code OutcomeMetricService} as v1 and only translate DTOs ({@code issueId} → {@code workItemId}), so this
 * guards the full create→list round-trip and the v1↔v2 DTO mapping.
 */
class WorkItemAssetsV2E2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;

    @BeforeEach
    void login() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-wisub@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void v2SubResourceFlow() {
        // Project (seeds the ENGINEERING workflow: asset_types=[github_pr], metric=null) via v1.
        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "WI sub v2 E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) projResp.getBody().get("id");

        // Create a Work Item via v2.
        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "V2 Work Item", "type", "PRD"), authHeaders), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workItemId = (String) createResp.getBody().get("id");
        assertThat(workItemId).isNotBlank();

        String assetsUrl = url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/assets");
        String stepRunsUrl = url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/step-runs");
        String metricUrl = url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/metric");

        // --- Assets: record (POST 201) then list (GET 200) ---
        var assetBody = Map.of(
                "type", "github_pr",
                "label", "Pull Request",
                "kind", "link",
                "ref", "https://github.com/org/repo/pull/1",
                "done", true);
        var createAssetResp = rest.exchange(assetsUrl, HttpMethod.POST,
                new HttpEntity<>(assetBody, authHeaders), Map.class);
        assertThat(createAssetResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createAssetResp.getBody().get("workItemId")).isEqualTo(workItemId);
        assertThat(createAssetResp.getBody().get("type")).isEqualTo("github_pr");
        assertThat(createAssetResp.getBody().get("kind")).isEqualTo("link");
        String assetId = (String) createAssetResp.getBody().get("id");
        assertThat(assetId).isNotBlank();

        var listAssetsResp = rest.exchange(assetsUrl, HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(listAssetsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listAssetsResp.getBody()).hasSize(1);

        // PATCH the asset (mark done false) — round-trips the v2 PatchAssetRequest mapping.
        var patchAssetResp = rest.exchange(assetsUrl + "/" + assetId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("done", false), authHeaders), Map.class);
        assertThat(patchAssetResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchAssetResp.getBody().get("done")).isEqualTo(false);

        // --- Step-runs: record (POST 201) then list (GET 200) ---
        var stepRunBody = Map.of(
                "stepKind", "skill",
                "skill", "conductor-coder",
                "status", "SUCCEEDED",
                "inputBrief", "Implement the feature",
                "reportedBy", "claude",
                "produced", List.of(Map.of("kind", "document", "ref", "doc-123")),
                "beforeAfter", Map.of("before", "TODO", "after", "IN_PROGRESS"),
                "flags", List.of(Map.of("level", "info", "message", "all good")));
        var createStepRunResp = rest.exchange(stepRunsUrl, HttpMethod.POST,
                new HttpEntity<>(stepRunBody, authHeaders), Map.class);
        assertThat(createStepRunResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createStepRunResp.getBody().get("workItemId")).isEqualTo(workItemId);
        assertThat(createStepRunResp.getBody().get("status")).isEqualTo("SUCCEEDED");
        assertThat(createStepRunResp.getBody().get("stepKind")).isEqualTo("skill");
        assertThat((List<?>) createStepRunResp.getBody().get("produced")).hasSize(1);
        assertThat((List<?>) createStepRunResp.getBody().get("flags")).hasSize(1);
        assertThat(((Map<?, ?>) createStepRunResp.getBody().get("beforeAfter")).get("after")).isEqualTo("IN_PROGRESS");

        var listStepRunsResp = rest.exchange(stepRunsUrl, HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(listStepRunsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listStepRunsResp.getBody()).hasSize(1);

        // --- Metric: ENGINEERING has metric=null, so the series GET still returns 2xx (empty observations). ---
        var metricResp = rest.exchange(metricUrl, HttpMethod.GET,
                new HttpEntity<>(authHeaders), Map.class);
        assertThat(metricResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(metricResp.getBody().get("observations")).isInstanceOf(List.class);
    }
}
