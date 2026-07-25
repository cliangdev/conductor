package com.conductor.integration.connector.gcp;

import com.conductor.exception.ForbiddenException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.connector.gcp.model.ArDockerImage;
import com.conductor.integration.connector.gcp.model.ArListDockerImagesResponse;
import com.conductor.integration.connector.gcp.model.ArListRepositoriesResponse;
import com.conductor.integration.connector.gcp.model.ArRepository;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.Job;
import com.google.cloud.run.v2.TaskTemplate;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GcpConnectorTest {

    private static final String IMAGE_REF = "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner:3";

    private static ConnectionContext ctx(String key) {
        return new ConnectionContext("proj", "gcp", "conn", key, null, null, Map.of(), null);
    }

    private static GcpConnector connectorWithFakeToken(RestTemplate restTemplate) {
        return new GcpConnector(restTemplate, null, c -> "fake-token");
    }

    // ---- verifyImage ----

    @Test
    void verifyImage_foundByMatchingTag_returnsExistsTrueWithLabelWarningOnly() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage image = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:abc123",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:abc123",
                List.of("3", "latest"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(image), null)));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isTrue();
        assertThat(result.protocolLabelPresent()).isFalse();
        assertThat(result.message()).contains("dev.conductor.runner.protocol");
    }

    @Test
    void verifyImage_tagNotAmongImages_returnsExistsFalse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage otherTag = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:def456",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:def456",
                List.of("2"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(otherTag), null)));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    @Test
    void verifyImage_repositoryNotFound_returnsExistsFalseWithMessage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    @Test
    void verifyImage_forbidden_throwsForbiddenExceptionMentioningArtifactRegistryReader() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        GcpConnector connector = connectorWithFakeToken(restTemplate);

        assertThatThrownBy(() -> connector.verifyImage(ctx("{}"), IMAGE_REF))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("artifactregistry.reader");
    }

    @Test
    void verifyImage_protocolLabelPresent_returnsExistsTrueAndLabelPresentTrue() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage image = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:abc123",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:abc123",
                List.of("3", "latest"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(image), null)));
        // Single-platform manifest (no manifest-list indirection).
        stubRegistryJson(restTemplate, "/manifests/sha256:abc123",
                "{\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                        + "\"config\":{\"digest\":\"sha256:configdigest\"}}");
        stubRegistryJson(restTemplate, "/blobs/sha256:configdigest",
                "{\"config\":{\"Labels\":{\"dev.conductor.runner.protocol\":\"1\"}}}");

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isTrue();
        assertThat(result.protocolLabelPresent()).isTrue();
        assertThat(result.message()).contains("verified").contains("protocol 1");
    }

    @Test
    void verifyImage_manifestList_followsLinuxAmd64PlatformEntry() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage image = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:abc123",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:abc123",
                List.of("3", "latest"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(image), null)));
        stubRegistryJson(restTemplate, "/manifests/sha256:abc123",
                "{\"mediaType\":\"application/vnd.docker.distribution.manifest.list.v2+json\",\"manifests\":["
                        + "{\"digest\":\"sha256:armentry\",\"platform\":{\"architecture\":\"arm64\",\"os\":\"linux\"}},"
                        + "{\"digest\":\"sha256:amd64entry\",\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}"
                        + "]}");
        stubRegistryJson(restTemplate, "/manifests/sha256:amd64entry",
                "{\"config\":{\"digest\":\"sha256:configdigest\"}}");
        stubRegistryJson(restTemplate, "/blobs/sha256:configdigest",
                "{\"config\":{\"Labels\":{\"dev.conductor.runner.protocol\":\"1\"}}}");

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.protocolLabelPresent()).isTrue();
    }

    @Test
    void verifyImage_manifestFetchThrows_degradesToWarningRatherThanFailing() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage image = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:abc123",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:abc123",
                List.of("3", "latest"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(image), null)));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isTrue();
        assertThat(result.protocolLabelPresent()).isFalse();
        assertThat(result.message()).contains("dev.conductor.runner.protocol");
    }

    @Test
    void verifyImage_labelMissingFromConfig_returnsLabelPresentFalse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArDockerImage image = new ArDockerImage(
                "projects/my-project/locations/us-central1/repositories/conductor-runners/dockerImages/conductor-runner@sha256:abc123",
                "us-central1-docker.pkg.dev/my-project/conductor-runners/conductor-runner@sha256:abc123",
                List.of("3", "latest"));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListDockerImagesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListDockerImagesResponse(List.of(image), null)));
        stubRegistryJson(restTemplate, "/manifests/sha256:abc123", "{\"config\":{\"digest\":\"sha256:configdigest\"}}");
        stubRegistryJson(restTemplate, "/blobs/sha256:configdigest", "{\"config\":{\"Labels\":{}}}");

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        GcpConnector.VerifyImageResult result = connector.verifyImage(ctx("{}"), IMAGE_REF);

        assertThat(result.exists()).isTrue();
        assertThat(result.protocolLabelPresent()).isFalse();
    }

    /** Stubs a Docker Registry v2 GET (manifest or blob) whose URI ends with {@code pathSuffix}. */
    private static void stubRegistryJson(RestTemplate restTemplate, String pathSuffix, String json) {
        when(restTemplate.exchange(
                        org.mockito.ArgumentMatchers.argThat(uri -> uri != null && uri.toString().endsWith(pathSuffix)),
                        eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
    }

    // ---- listRepositories ----

    @Test
    void listRepositories_mapsNameAndFormat() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ArRepository repo = new ArRepository(
                "projects/my-project/locations/us-central1/repositories/conductor-runners", "DOCKER");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListRepositoriesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListRepositoriesResponse(List.of(repo), null)));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        List<Map<String, String>> result = connector.listRepositories(ctx("{}"), "my-project", "us-central1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "conductor-runners").containsEntry("format", "DOCKER");
    }

    @Test
    void listRepositories_emptyBody_returnsEmptyList() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(ArListRepositoriesResponse.class)))
                .thenReturn(ResponseEntity.ok(new ArListRepositoriesResponse(null, null)));

        GcpConnector connector = connectorWithFakeToken(restTemplate);
        List<Map<String, String>> result = connector.listRepositories(ctx("{}"), "my-project", "us-central1");

        assertThat(result).isEmpty();
    }

    // ---- checkHealth ----

    @Test
    void checkHealth_missingKey_returnsSetupRequired() {
        GcpConnector connector = new GcpConnector(mock(RestTemplate.class));
        assertThat(connector.checkHealth(ctx(null))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    void checkHealth_malformedJson_returnsSetupRequired() {
        GcpConnector connector = new GcpConnector(mock(RestTemplate.class));
        assertThat(connector.checkHealth(ctx("not json"))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    void checkHealth_validJsonButWrongCredentialType_returnsSetupRequired() {
        GcpConnector connector = new GcpConnector(mock(RestTemplate.class));
        assertThat(connector.checkHealth(ctx("{\"type\": \"authorized_user\"}")))
                .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    // ---- imageRef parsing ----

    @Test
    void parseImageRef_rejectsNonArtifactRegistryHost() {
        assertThatThrownBy(() -> GcpConnector.parseImageRef("docker.io/library/nginx:latest"))
                .hasMessageContaining("Artifact Registry");
    }

    // ---- ensureJob response mapping (what "Sync to latest image" depends on) ----

    private static Job jobWithImage(String image) {
        Container container = Container.newBuilder().setImage(image).build();
        TaskTemplate taskTemplate = TaskTemplate.newBuilder().addContainers(container).build();
        return Job.newBuilder().setTemplate(ExecutionTemplate.newBuilder().setTemplate(taskTemplate)).build();
    }

    @Test
    void resolvedImageOf_returnsTheFirstContainersImage() {
        Job job = jobWithImage("us-central1-docker.pkg.dev/proj/repo/claude-runner@sha256:abc123");
        assertThat(GcpConnector.resolvedImageOf(job))
                .isEqualTo("us-central1-docker.pkg.dev/proj/repo/claude-runner@sha256:abc123");
    }

    @Test
    void resolvedImageOf_noContainers_returnsNullRatherThanThrowing() {
        Job job = Job.newBuilder()
                .setTemplate(ExecutionTemplate.newBuilder().setTemplate(TaskTemplate.newBuilder()))
                .build();
        assertThat(GcpConnector.resolvedImageOf(job)).isNull();
    }

    @Test
    void updateTimeOf_convertsProtoTimestampToOffsetDateTimeInUtc() {
        Job job = jobWithImage("img:1").toBuilder()
                .setUpdateTime(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_000_000).build())
                .build();
        OffsetDateTime updateTime = GcpConnector.updateTimeOf(job);
        assertThat(updateTime).isEqualTo(OffsetDateTime.parse("2023-11-14T22:13:20.123Z"));
    }

    @Test
    void updateTimeOf_absentOnResponse_returnsNull() {
        Job job = jobWithImage("img:1");
        assertThat(GcpConnector.updateTimeOf(job)).isNull();
    }
}
