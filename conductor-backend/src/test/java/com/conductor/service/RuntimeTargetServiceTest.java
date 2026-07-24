package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateRuntimeTargetRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeTargetServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTION_ID = "conn-1";
    private static final String IMAGE = "us-central1-docker.pkg.dev/customer-proj/repo/image:1";

    @Mock private RuntimeTargetRepository repository;
    @Mock private ConnectionService connectionService;
    @Mock private GcpConnector gcpConnector;
    @Mock private CloudRunClientFactory cloudRunClientFactory;

    private RuntimeTargetService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeTargetService(repository, connectionService, Optional.of(gcpConnector),
                Optional.<LocalGcpConnector>empty(), Optional.of(cloudRunClientFactory), new ObjectMapper());
    }

    private Connection activeGcpConnection() {
        Connection c = new Connection();
        c.setId(CONNECTION_ID);
        c.setProjectId(PROJECT_ID);
        c.setConnectorId("gcp");
        c.setStatus("ACTIVE");
        return c;
    }

    private CreateRuntimeTargetRequest createRequest(String name) {
        return new CreateRuntimeTargetRequest(name, CreateRuntimeTargetRequest.ProviderEnum.GCP_CLOUD_RUN,
                CONNECTION_ID, "customer-proj", "us-central1", IMAGE);
    }

    private void stubSaveReturnsArgument() {
        when(repository.save(any(RuntimeTarget.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- create: validation ----

    @Test
    void create_reservedName_rejectedWithConflict() {
        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("cloud-run")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void create_badSlug_rejectedWithBusinessException() {
        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("Not_A_Slug")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_duplicateName_rejectedWithConflict() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(true);

        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("my-target")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_connectionNotInProject_rejected() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection foreignConnection = activeGcpConnection();
        foreignConnection.setProjectId("other-proj");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(foreignConnection));

        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("my-target")))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    void create_connectionWrongConnector_rejected() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection wrongConnector = activeGcpConnection();
        wrongConnector.setConnectorId("github");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(wrongConnector));

        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("my-target")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("gcp");
    }

    @Test
    void create_connectionNotActive_rejected() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection inactive = activeGcpConnection();
        inactive.setStatus("DISCONNECTED");
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.create(PROJECT_ID, createRequest("my-target")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE");
    }

    // ---- create: provisioning outcomes ----

    @Test
    void create_imageExists_provisionsToActive() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(eq(ctx), eq(IMAGE)))
                .thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        java.time.OffsetDateTime updatedAt = java.time.OffsetDateTime.parse("2026-07-24T03:00:00Z");
        when(gcpConnector.ensureJob(eq(ctx), any(GcpConnector.EnsureJobSpec.class)))
                .thenReturn(new GcpConnector.EnsureJobResult(
                        "projects/customer-proj/locations/us-central1/jobs/conductor-my-target",
                        IMAGE + "@sha256:resolved123", updatedAt));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getConnectionId()).isEqualTo(CONNECTION_ID);
        RuntimeTargetService.TargetRuntimeConfig config = service.configOf(saved);
        assertThat(config.jobName()).isEqualTo("conductor-my-target");
        assertThat(config.gcpProjectId()).isEqualTo("customer-proj");
        // The resolved digest GCP echoed back, distinct from the configured tag (IMAGE) — proves
        // "what's actually running" is threaded through, not just the requested image string.
        assertThat(config.resolvedImage()).isEqualTo(IMAGE + "@sha256:resolved123");
        assertThat(config.lastProvisionedAt()).isEqualTo(updatedAt);
    }

    @Test
    void create_defaultsJobNameWhenAbsent() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any())).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(
                new GcpConnector.EnsureJobResult("jobs/conductor-my-target", IMAGE, java.time.OffsetDateTime.now()));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(service.configOf(saved).jobName()).isEqualTo("conductor-my-target");
    }

    @Test
    void create_explicitJobName_isUsed() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any())).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(
                new GcpConnector.EnsureJobResult("jobs/custom-job", IMAGE, java.time.OffsetDateTime.now()));
        stubSaveReturnsArgument();

        CreateRuntimeTargetRequest request = createRequest("my-target");
        request.setJobName("custom-job");
        RuntimeTarget saved = service.create(PROJECT_ID, request);

        assertThat(service.configOf(saved).jobName()).isEqualTo("custom-job");
        ArgumentCaptor<GcpConnector.EnsureJobSpec> specCaptor = ArgumentCaptor.forClass(GcpConnector.EnsureJobSpec.class);
        verify(gcpConnector).ensureJob(eq(ctx), specCaptor.capture());
        assertThat(specCaptor.getValue().jobName()).isEqualTo("custom-job");
    }

    @Test
    void create_imageMissing_provisionsToError() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(eq(ctx), eq(IMAGE)))
                .thenReturn(new GcpConnector.VerifyImageResult(false, false, "Image not found in Artifact Registry"));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        assertThat(saved.getErrorMessage()).contains("not found");
        verify(gcpConnector, never()).ensureJob(any(), any());
    }

    @Test
    void create_forbiddenVerifyingImage_provisionsToErrorMentioningArtifactRegistryReader() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(eq(ctx), eq(IMAGE)))
                .thenThrow(new ForbiddenException(
                        "Missing permission to read Artifact Registry — grant roles/artifactregistry.reader"));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        assertThat(saved.getErrorMessage()).contains("artifactregistry.reader");
    }

    @Test
    void create_ensureJobThrowsIllegalState_provisionsToError() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any())).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenThrow(new IllegalStateException("SDK failure: quota exceeded"));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        assertThat(saved.getErrorMessage()).contains("quota exceeded");
    }

    @Test
    void create_httpClientErrorException_provisionsToError() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
    }

    @Test
    void create_missingProtocolLabel_storesWarningButStaysActive() {
        when(repository.existsByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(false);
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(eq(ctx), eq(IMAGE))).thenReturn(new GcpConnector.VerifyImageResult(
                true, false, "Image found. Could not verify the dev.conductor.runner.protocol OCI label."));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(
                new GcpConnector.EnsureJobResult("jobs/conductor-my-target", IMAGE, java.time.OffsetDateTime.now()));
        stubSaveReturnsArgument();

        RuntimeTarget saved = service.create(PROJECT_ID, createRequest("my-target"));

        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(service.configOf(saved).warnings()).anyMatch(w -> w.contains("protocol"));
    }

    // ---- provisionById (idempotent retry) ----

    @Test
    void provisionById_retryAfterError_canBecomeActive() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setConnectionId(CONNECTION_ID);
        target.setStatus(RuntimeTargetStatus.ERROR);
        target.setErrorMessage("previously failed");
        target.setConfigJson("{\"gcpProjectId\":\"customer-proj\",\"region\":\"us-central1\","
                + "\"jobName\":\"conductor-my-target\",\"image\":\"" + IMAGE + "\"}");
        when(repository.findById("target-1")).thenReturn(Optional.of(target));
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any())).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(
                new GcpConnector.EnsureJobResult("jobs/conductor-my-target", IMAGE, java.time.OffsetDateTime.now()));
        stubSaveReturnsArgument();

        RuntimeTarget result = service.provisionById(PROJECT_ID, "target-1");

        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        assertThat(result.getErrorMessage()).isNull();
    }

    // ---- update ----

    @Test
    void update_configChange_setsProvisioningAndReprovisions() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setConnectionId(CONNECTION_ID);
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConfigJson("{\"gcpProjectId\":\"customer-proj\",\"region\":\"us-central1\","
                + "\"jobName\":\"conductor-my-target\",\"image\":\"" + IMAGE + "\"}");
        when(repository.findById("target-1")).thenReturn(Optional.of(target));
        Connection connection = activeGcpConnection();
        when(connectionService.getById(CONNECTION_ID)).thenReturn(Optional.of(connection));
        ConnectionContext ctx = new ConnectionContext(PROJECT_ID, "gcp", CONNECTION_ID, "key", null, null, Map.of(), null);
        when(connectionService.toContext(connection)).thenReturn(ctx);
        when(gcpConnector.verifyImage(any(), any())).thenReturn(new GcpConnector.VerifyImageResult(true, true, null));
        when(gcpConnector.ensureJob(any(), any())).thenReturn(
                new GcpConnector.EnsureJobResult("jobs/conductor-my-target", IMAGE, java.time.OffsetDateTime.now()));
        stubSaveReturnsArgument();

        UpdateRuntimeTargetRequest request = new UpdateRuntimeTargetRequest();
        request.setRegion("us-east1");
        RuntimeTarget result = service.update(PROJECT_ID, "target-1", request);

        assertThat(service.configOf(result).region()).isEqualTo("us-east1");
        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        verify(cloudRunClientFactory).evict(CONNECTION_ID);
    }

    @Test
    void update_noFieldsChanged_isNoopAndDoesNotReprovision() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConfigJson("{\"gcpProjectId\":\"customer-proj\",\"region\":\"us-central1\","
                + "\"jobName\":\"conductor-my-target\",\"image\":\"" + IMAGE + "\"}");
        when(repository.findById("target-1")).thenReturn(Optional.of(target));

        RuntimeTarget result = service.update(PROJECT_ID, "target-1", new UpdateRuntimeTargetRequest());

        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ACTIVE);
        verify(repository, never()).save(any());
        verify(gcpConnector, never()).verifyImage(any(), any());
    }

    // ---- delete ----

    @Test
    void delete_removesRowAndEvictsClientCache_doesNotTouchGcp() {
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setConnectionId(CONNECTION_ID);
        when(repository.findById("target-1")).thenReturn(Optional.of(target));

        service.delete(PROJECT_ID, "target-1");

        verify(repository).delete(target);
        verify(cloudRunClientFactory).evict(CONNECTION_ID);
        verify(gcpConnector, never()).ensureJob(any(), any());
    }

    // ---- connection deletion / orphaned targets ----

    @Test
    void onConnectionDeleted_flipsReferencingTargetsToErrorAndEvictsClients() {
        stubSaveReturnsArgument();
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setConnectionId(CONNECTION_ID);
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        when(repository.findByConnectionId(CONNECTION_ID)).thenReturn(java.util.List.of(target));

        service.onConnectionDeleted(CONNECTION_ID);

        assertThat(target.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        assertThat(target.getErrorMessage()).contains("connection backing this target was removed");
        verify(repository).save(target);
        verify(cloudRunClientFactory).evict(CONNECTION_ID);
    }

    @Test
    void provisionById_orphanedTargetWithNullConnection_setsErrorInsteadOfThrowing() {
        stubSaveReturnsArgument();
        RuntimeTarget target = new RuntimeTarget();
        target.setId("target-1");
        target.setProjectId(PROJECT_ID);
        target.setConnectionId(null);
        target.setStatus(RuntimeTargetStatus.ERROR);
        target.setConfigJson("{\"gcpProjectId\":\"p\",\"region\":\"r\",\"jobName\":\"j\",\"image\":\"i\"}");
        when(repository.findById("target-1")).thenReturn(Optional.of(target));

        RuntimeTarget result = service.provisionById(PROJECT_ID, "target-1");

        assertThat(result.getStatus()).isEqualTo(RuntimeTargetStatus.ERROR);
        assertThat(result.getErrorMessage()).contains("connection no longer exists");
        verify(connectionService, never()).getById(any());
        verify(gcpConnector, never()).verifyImage(any(), any());
    }

    // ---- targetNames / findActiveByName ----

    @Test
    void targetNames_returnsAllRegardlessOfStatus() {
        RuntimeTarget active = new RuntimeTarget();
        active.setName("active-target");
        active.setStatus(RuntimeTargetStatus.ACTIVE);
        RuntimeTarget provisioning = new RuntimeTarget();
        provisioning.setName("provisioning-target");
        provisioning.setStatus(RuntimeTargetStatus.PROVISIONING);
        when(repository.findByProjectId(PROJECT_ID)).thenReturn(java.util.List.of(active, provisioning));

        assertThat(service.targetNames(PROJECT_ID)).containsExactlyInAnyOrder("active-target", "provisioning-target");
    }

    @Test
    void findActiveByName_filtersOutNonActive() {
        RuntimeTarget provisioning = new RuntimeTarget();
        provisioning.setName("my-target");
        provisioning.setStatus(RuntimeTargetStatus.PROVISIONING);
        when(repository.findByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(Optional.of(provisioning));

        assertThat(service.findActiveByName(PROJECT_ID, "my-target")).isEmpty();
    }
}
