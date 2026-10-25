package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.exception.BusinessException;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
import com.conductor.generated.model.RuntimeTargetRender;
import com.conductor.generated.model.RuntimeTargetResponse;
import com.conductor.generated.model.UpdateRuntimeTargetRequest;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.connector.gcp.GcpConnector;
import com.conductor.integration.connector.local.LocalGcpConnector;
import com.conductor.repository.RuntimeTargetRepository;
import com.conductor.workflow.CloudRunClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Render-job provisioning on a gcp-cloud-run runtime target (config keys renderImage / render). */
@ExtendWith(MockitoExtension.class)
class RuntimeTargetServiceRenderTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTION_ID = "conn-1";
    private static final String IMAGE = "us-central1-docker.pkg.dev/customer-proj/repo/claude:1";
    private static final String RENDER_IMAGE = "us-central1-docker.pkg.dev/customer-proj/repo/conductor-render:1";

    @Mock private RuntimeTargetRepository repository;
    @Mock private ConnectionService connectionService;
    @Mock private GcpConnector gcpConnector;
    @Mock private CloudRunClientFactory cloudRunClientFactory;

    private RuntimeTargetService service;
    private ConnectionContext ctx;

    @BeforeEach
    void setUp() {
        service = new RuntimeTargetService(repository, connectionService, Optional.of(gcpConnector),
                Optional.<LocalGcpConnector>empty(), Optional.of(cloudRunClientFactory), new ObjectMapper());
        Connection connection = new Connection();
        connection.setId(CONNECTION_ID);
        connection.setProjectId(PROJECT_ID);
        connection.setConnectorId("gcp");
        connection.setStatus("ACTIVE");
        org.mockito.Mockito.lenient().when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        org.mockito.Mockito.lenient().when(connectionService.toContext(connection)).thenReturn(ctx);
        org.mockito.Mockito.lenient().when(repository.save(any(RuntimeTarget.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateRuntimeTargetRequest createRequest(String renderImage) {
        CreateRuntimeTargetRequest request = new CreateRuntimeTargetRequest("my-target",
                CreateRuntimeTargetRequest.ProviderEnum.GCP_CLOUD_RUN, CONNECTION_ID, "customer-proj",
                "us-central1", IMAGE);
        request.setRenderImage(renderImage);
        return request;
    }

    private GcpConnector.EnsureJobResult ensured(String job, String image) {
        return new GcpConnector.EnsureJobResult("jobs/" + job, image, OffsetDateTime.now());
    }

    private void claudeProvisionsFine() {
        when(gcpConnector.verifyImage(any(), eq(IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().equals("conductor-my-target"))))
                .thenReturn(ensured("conductor-my-target", IMAGE + "@sha256:claude"));
    }

    private RuntimeTarget existingTarget(String configJson) {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setName("my-target");
        target.setProvider("gcp-cloud-run");
        target.setConnectionId(CONNECTION_ID);
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConfigJson(configJson);
        when(repository.findById("target-1")).thenReturn(Optional.of(target));
        return target;
    }

    private static final String BASE_CONFIG = "\"gcpProjectId\":\"customer-proj\",\"region\":\"us-central1\","
            + "\"jobName\":\"conductor-my-target\",\"image\":\"" + IMAGE + "\"";

    @Test
    void noRenderImage_renderIsNone_andNoRenderJobIsEnsured() {
        claudeProvisionsFine();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(null));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        RuntimeTargetService.TargetRuntimeConfig config = service.configOf(saved);
        assertThat(config.render().status()).isEqualTo("NONE");
        assertThat(config.renderImage()).isNull();
        verify(gcpConnector, times(1)).ensureJob(any(), any());
        RuntimeTargetResponse response = service.toResponse(saved);
        assertThat(response.getRender().getStatus()).isEqualTo(RuntimeTargetRender.StatusEnum.NONE);
    }

    @Test
    void renderImage_ensuresRenderJobWithStepEntrypointAndResources_andIsReady() {
        claudeProvisionsFine();
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().equals("conductor-my-target-render"))))
                .thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE + "@sha256:render"));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(RENDER_IMAGE));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        RuntimeTargetService.TargetRuntimeConfig config = service.configOf(saved);
        assertThat(config.render().status()).isEqualTo("READY");
        assertThat(config.render().resolvedImage()).isEqualTo(RENDER_IMAGE + "@sha256:render");
        assertThat(config.render().lastProvisionedAt()).isNotNull();
        assertThat(config.render().warnings()).isEmpty();
        assertThat(config.renderJobName()).isEqualTo("conductor-my-target-render");

        ArgumentCaptor<GcpConnector.EnsureJobSpec> captor = ArgumentCaptor.forClass(GcpConnector.EnsureJobSpec.class);
        verify(gcpConnector, times(2)).ensureJob(eq(ctx), captor.capture());
        GcpConnector.EnsureJobSpec render = captor.getAllValues().get(1);
        assertThat(render.image()).isEqualTo(RENDER_IMAGE);
        assertThat(render.command()).containsExactly("conductor-step-entrypoint");
        assertThat(render.cpu()).isEqualTo("2");
        assertThat(render.memory()).isEqualTo("4Gi");
        assertThat(render.taskTimeoutSeconds()).isEqualTo(38 * 60);
        // the claude job keeps its defaults
        GcpConnector.EnsureJobSpec claude = captor.getAllValues().get(0);
        assertThat(claude.command()).isNull();
        assertThat(claude.cpu()).isNull();
        assertThat(claude.taskTimeoutSeconds()).isNull();

        RuntimeTargetResponse response = service.toResponse(saved);
        assertThat(response.getRenderImage()).isEqualTo(RENDER_IMAGE);
        assertThat(response.getRenderJobName()).isEqualTo("conductor-my-target-render");
        assertThat(response.getRender().getStatus()).isEqualTo(RuntimeTargetRender.StatusEnum.READY);
    }

    @Test
    void renderImageMissingProtocolLabel_isAWarning_stillReady() {
        claudeProvisionsFine();
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(
                true, false, "Image found. Could not verify the dev.conductor.runner.protocol OCI label."));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().endsWith("-render"))))
                .thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(RENDER_IMAGE));

        RuntimeTargetService.RenderConfig render = service.configOf(saved).render();
        assertThat(render.status()).isEqualTo("READY");
        assertThat(render.warnings()).anyMatch(w -> w.contains("protocol"));
    }

    @Test
    void renderImageNotOnArtifactRegistry_renderErrorsWithTheParseMessage_targetStaysActive_claudeJobStillProvisioned() {
        claudeProvisionsFine();
        String bad = "ghcr.io/cliangdev/conductor-render:1";
        when(gcpConnector.verifyImage(any(), eq(bad))).thenThrow(new BusinessException(
                "Not an Artifact Registry image reference (expected a *-docker.pkg.dev host): " + bad));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(bad));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(saved.getErrorMessage()).isNull();
        RuntimeTargetService.RenderConfig render = service.configOf(saved).render();
        assertThat(render.status()).isEqualTo("ERROR");
        assertThat(render.error()).contains("*-docker.pkg.dev");
        // only the claude job was ensured
        verify(gcpConnector, times(1)).ensureJob(any(), any());
        assertThat(service.toResponse(saved).getRender().getError()).contains("*-docker.pkg.dev");
    }

    @Test
    void renderImageNotFound_rendersErrorWithMessage_targetStaysActive() {
        claudeProvisionsFine();
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(
                new GcpConnector.VerifyImageResult(false, false, "Image " + RENDER_IMAGE + " not found"));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(RENDER_IMAGE));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(service.configOf(saved).render().status()).isEqualTo("ERROR");
        assertThat(service.configOf(saved).render().error()).contains("not found");
    }

    @Test
    void renderJobEnsureFails_renderErrors_targetStatusUnchanged() {
        claudeProvisionsFine();
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().endsWith("-render"))))
                .thenThrow(new IllegalStateException("Failed to ensure Cloud Run Job: quota exceeded"));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(RENDER_IMAGE));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(service.configOf(saved).render().status()).isEqualTo("ERROR");
        assertThat(service.configOf(saved).render().error()).contains("quota exceeded");
    }

    @Test
    void updatingRenderImage_reprovisionsTheRenderJob() {
        RuntimeTarget target = existingTarget("{" + BASE_CONFIG + "}");
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().endsWith("-render"))))
                .thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE + "@sha256:r"));

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRenderImage(RENDER_IMAGE);
        RuntimeTarget result = service.update(PROJECT_ID, "target-1", request);

        assertThat(result).isSameAs(target);
        assertThat(service.configOf(result).renderImage()).isEqualTo(RENDER_IMAGE);
        assertThat(service.configOf(result).render().status()).isEqualTo("READY");
        verify(cloudRunClientFactory).evict(CONNECTION_ID);
    }

    @Test
    void clearingRenderImage_rendersNone_andDoesNotDeleteOrTouchTheExistingRenderJob() {
        existingTarget("{" + BASE_CONFIG + ",\"renderImage\":\"" + RENDER_IMAGE + "\",\"render\":"
                + "{\"status\":\"READY\",\"resolvedImage\":\"x\"}}");

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRenderImage("");
        RuntimeTarget result = service.update(PROJECT_ID, "target-1", request);

        RuntimeTargetService.TargetRuntimeConfig config = service.configOf(result);
        assertThat(config.renderImage()).isNull();
        assertThat(config.render().status()).isEqualTo("NONE");
        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        verify(gcpConnector, never()).ensureJob(any(), any());
    }

    @Test
    void renderOnlyUpdate_keepsTargetActive_andNeverReverifiesOrReensuresTheClaudeJob() {
        RuntimeTarget target = existingTarget("{" + BASE_CONFIG + "}");
        List<RuntimeTargetStatus> seen = new java.util.ArrayList<>();
        when(repository.save(any(RuntimeTarget.class))).thenAnswer(inv -> {
            RuntimeTarget t = inv.getArgument(0);
            seen.add(t.getStatus());
            return t;
        });
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().endsWith("-render"))))
                .thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE + "@sha256:r"));

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRenderImage(RENDER_IMAGE);
        service.update(PROJECT_ID, "target-1", request);

        assertThat(seen).isNotEmpty().containsOnly(RuntimeTargetStatus.ACTIVE);
        assertThat(target.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        verify(gcpConnector, never()).verifyImage(any(), eq(IMAGE));
        verify(gcpConnector, times(1)).ensureJob(any(), any());
    }

    @Test
    void renderOnlyUpdate_renderFailure_leavesTargetActive() {
        RuntimeTarget target = existingTarget("{" + BASE_CONFIG + "}");
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenThrow(new IllegalStateException("boom"));

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRenderImage(RENDER_IMAGE);
        RuntimeTarget result = service.update(PROJECT_ID, "target-1", request);

        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(target.getErrorMessage()).isNull();
        assertThat(service.configOf(result).render().status()).isEqualTo("ERROR");
        verify(gcpConnector, never()).verifyImage(any(), eq(IMAGE));
    }

    @Test
    void resavingTheSameRenderImage_resyncsTheRenderJob() {
        existingTarget("{" + BASE_CONFIG + ",\"renderImage\":\"" + RENDER_IMAGE + "\",\"render\":"
                + "{\"status\":\"ERROR\",\"error\":\"old\"}}");
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE + "@sha256:n"));

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRenderImage(RENDER_IMAGE);
        RuntimeTarget result = service.update(PROJECT_ID, "target-1", request);

        verify(gcpConnector, times(1)).ensureJob(any(), any());
        assertThat(service.configOf(result).render().status()).isEqualTo("READY");
    }

    @Test
    void omittingRenderImage_withNoOtherChange_doesNothing() {
        existingTarget("{" + BASE_CONFIG + ",\"renderImage\":\"" + RENDER_IMAGE + "\"}");

        service.update(PROJECT_ID, "target-1", new UpdateRuntimeTargetRequest());

        verify(gcpConnector, never()).ensureJob(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void renderJobName_longTargetName_isDerivedWithinSixtyThreeCharsAndStable() {
        String longJob = "conductor-" + "a".repeat(52);
        String derived = RuntimeTargetService.renderJobNameFor(longJob);

        assertThat(derived).hasSizeLessThanOrEqualTo(63).endsWith("-render").startsWith("conductor-aaa");
        assertThat(derived).matches("^[a-z]([-a-z0-9]*[a-z0-9])?$");
        assertThat(RuntimeTargetService.renderJobNameFor(longJob)).isEqualTo(derived);
        assertThat(RuntimeTargetService.renderJobNameFor(longJob + "b")).isNotEqualTo(derived);
        assertThat(RuntimeTargetService.renderJobNameFor("conductor-my-target")).isEqualTo("conductor-my-target-render");
    }

    @Test
    void longJobName_provisionsRenderJobUnderDerivedName() {
        String longName = "a".repeat(50);
        String job = "conductor-" + longName;
        when(gcpConnector.verifyImage(any(), eq(IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(ensured(job, IMAGE));
        CreateRuntimeTargetRequest request = new CreateRuntimeTargetRequest(longName,
                CreateRuntimeTargetRequest.ProviderEnum.GCP_CLOUD_RUN, CONNECTION_ID, "customer-proj", "us-central1", IMAGE);
        request.setRenderImage(RENDER_IMAGE);

        RuntimeTarget saved = service.create(PROJECT_ID, request);

        ArgumentCaptor<GcpConnector.EnsureJobSpec> specs = ArgumentCaptor.forClass(GcpConnector.EnsureJobSpec.class);
        verify(gcpConnector, times(2)).ensureJob(any(), specs.capture());
        String renderJob = specs.getAllValues().get(1).jobName();
        assertThat(renderJob).hasSizeLessThanOrEqualTo(63).isEqualTo(RuntimeTargetService.renderJobNameFor(job));
        assertThat(service.configOf(saved).renderJobName()).isEqualTo(renderJob);
        assertThat(service.configOf(saved).render().status()).isEqualTo("READY");
    }

    @Test
    void syncToLatestImage_provisionById_resyncsTheRenderJobToo() {
        existingTarget("{" + BASE_CONFIG + ",\"renderImage\":\"" + RENDER_IMAGE + "\",\"render\":"
                + "{\"status\":\"ERROR\",\"error\":\"old\"}}");
        claudeProvisionsFine();
        when(gcpConnector.verifyImage(any(), eq(RENDER_IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), org.mockito.ArgumentMatchers.argThat(
                (GcpConnector.EnsureJobSpec s) -> s != null && s.jobName().endsWith("-render"))))
                .thenReturn(ensured("conductor-my-target-render", RENDER_IMAGE + "@sha256:new"));

        RuntimeTarget result = service.provisionById(PROJECT_ID, "target-1");

        RuntimeTargetService.RenderConfig render = service.configOf(result).render();
        assertThat(render.status()).isEqualTo("READY");
        assertThat(render.error()).isNull();
        assertThat(render.resolvedImage()).endsWith("@sha256:new");
    }

    @Test
    void claudeImageMissing_targetErrors_andRenderJobIsNotProvisioned() {
        when(gcpConnector.verifyImage(any(), eq(IMAGE))).thenReturn(
                new GcpConnector.VerifyImageResult(false, false, "Image not found"));

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest(RENDER_IMAGE));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        verify(gcpConnector, never()).ensureJob(any(), any());
        assertThat(List.of(service.configOf(saved).render().status())).containsExactly("NONE");
    }
}
