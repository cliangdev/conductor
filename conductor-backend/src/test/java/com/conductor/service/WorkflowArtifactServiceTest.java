package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowArtifact;
import com.conductor.entity.WorkflowArtifactStatus;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkflowArtifactRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowArtifactServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String PROJECT_ID = "proj-1";

    @Mock private WorkflowArtifactRepository repository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StorageService storageService;

    private WorkflowArtifactService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowArtifactService(repository, runRepository, storageService, "http://localhost:8080");

        Project project = new Project();
        project.setId(PROJECT_ID);
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setProject(project);
        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setWorkflow(workflow);
        // Only the create() tests need these — lenient so the other tests (complete/resolve/etc.)
        // don't fail on "unnecessary stubbing".
        lenient().when(runRepository.findByIdWithWorkflow(RUN_ID)).thenReturn(Optional.of(run));
        // save() echoes back whatever was passed in (a real JpaRepository would too, after
        // @PrePersist assigns an id — simulate that here so create() has an artifactId to return).
        lenient().when(repository.save(any(WorkflowArtifact.class))).thenAnswer(inv -> {
            WorkflowArtifact a = inv.getArgument(0);
            if (a.getId() == null) a.setId("artifact-" + System.identityHashCode(a));
            return a;
        });
    }

    @Test
    void create_gcsBacked_returnsSignedUploadUrlAndPendingRow() {
        when(repository.findByRunIdAndName(RUN_ID, "report")).thenReturn(Optional.empty());
        when(storageService.generateSignedUploadUrl(eq("workflow-artifacts/proj-1/run-1/report"), eq("application/json"), anyInt()))
                .thenReturn("https://storage.example/signed-put-url");

        WorkflowArtifactService.ArtifactCreateResult result =
                service.create(RUN_ID, "build", "jobrun-1", "report", "application/json", 42L);

        assertThat(result.uploadUrl()).isEqualTo("https://storage.example/signed-put-url");
        assertThat(result.artifactId()).isNotBlank();

        ArgumentCaptor<WorkflowArtifact> captor = ArgumentCaptor.forClass(WorkflowArtifact.class);
        verify(repository).save(captor.capture());
        WorkflowArtifact saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo(RUN_ID);
        assertThat(saved.getJobId()).isEqualTo("build");
        assertThat(saved.getJobRunId()).isEqualTo("jobrun-1");
        assertThat(saved.getGcsPath()).isEqualTo("workflow-artifacts/proj-1/run-1/report");
        assertThat(saved.getStatus()).isEqualTo(WorkflowArtifactStatus.PENDING);
        assertThat(saved.getSizeBytes()).isEqualTo(42L);
    }

    @Test
    void create_localStorage_fallsBackToInternalPassthroughUrl() {
        when(repository.findByRunIdAndName(RUN_ID, "report")).thenReturn(Optional.empty());
        when(storageService.generateSignedUploadUrl(anyString(), any(), anyInt())).thenReturn(null);

        WorkflowArtifactService.ArtifactCreateResult result =
                service.create(RUN_ID, "build", "jobrun-1", "report", null, null);

        assertThat(result.uploadUrl())
                .isEqualTo("http://localhost:8080/internal/v1/workflow-runs/run-1/artifacts/" + result.artifactId() + "/content");
    }

    @Test
    void create_invalidName_rejectedBeforeTouchingStorageOrRepository() {
        assertThatThrownBy(() -> service.create(RUN_ID, "build", "jobrun-1", "../evil", "application/json", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("../evil");

        verifyNoInteractions(storageService);
        verify(repository, never()).save(any());
    }

    @Test
    void create_sameRunAndName_reusesExistingRow() {
        WorkflowArtifact existing = new WorkflowArtifact();
        existing.setId("artifact-existing");
        existing.setRunId(RUN_ID);
        existing.setName("report");
        existing.setStatus(WorkflowArtifactStatus.UPLOADED);
        when(repository.findByRunIdAndName(RUN_ID, "report")).thenReturn(Optional.of(existing));
        when(storageService.generateSignedUploadUrl(anyString(), any(), anyInt())).thenReturn("https://storage.example/put");

        WorkflowArtifactService.ArtifactCreateResult result =
                service.create(RUN_ID, "build", "jobrun-2", "report", null, null);

        // Retried step declares the same artifact again — reuses the row (same id), reset to PENDING.
        assertThat(result.artifactId()).isEqualTo("artifact-existing");
        assertThat(existing.getStatus()).isEqualTo(WorkflowArtifactStatus.PENDING);
        assertThat(existing.getJobRunId()).isEqualTo("jobrun-2");
    }

    @Test
    void complete_marksUploaded() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setId("artifact-1");
        artifact.setStatus(WorkflowArtifactStatus.PENDING);
        when(repository.findById("artifact-1")).thenReturn(Optional.of(artifact));

        service.complete("artifact-1");

        assertThat(artifact.getStatus()).isEqualTo(WorkflowArtifactStatus.UPLOADED);
        verify(repository).save(artifact);
    }

    @Test
    void complete_calledTwice_isIdempotent() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setId("artifact-1");
        artifact.setStatus(WorkflowArtifactStatus.PENDING);
        when(repository.findById("artifact-1")).thenReturn(Optional.of(artifact));

        service.complete("artifact-1");
        service.complete("artifact-1");

        assertThat(artifact.getStatus()).isEqualTo(WorkflowArtifactStatus.UPLOADED);
        // Second call is a no-op (already UPLOADED) — save only happens on the first transition.
        verify(repository, times(1)).save(artifact);
    }

    @Test
    void complete_unknownArtifactId_isNoOp() {
        when(repository.findById("does-not-exist")).thenReturn(Optional.empty());

        service.complete("does-not-exist");

        verify(repository, never()).save(any());
    }

    @Test
    void resolveDownloadUrl_uploadedArtifact_returnsSignedUrl() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setGcsPath("workflow-artifacts/proj-1/run-1/report");
        artifact.setStatus(WorkflowArtifactStatus.UPLOADED);
        when(repository.findByRunIdAndName(RUN_ID, "report")).thenReturn(Optional.of(artifact));
        when(storageService.generateSignedUrl("workflow-artifacts/proj-1/run-1/report", 60))
                .thenReturn("https://storage.example/signed-get-url");

        Optional<String> result = service.resolveDownloadUrl(RUN_ID, "report");

        assertThat(result).contains("https://storage.example/signed-get-url");
    }

    @Test
    void resolveDownloadUrl_pendingArtifact_returnsEmpty() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setStatus(WorkflowArtifactStatus.PENDING);
        when(repository.findByRunIdAndName(RUN_ID, "report")).thenReturn(Optional.of(artifact));

        assertThat(service.resolveDownloadUrl(RUN_ID, "report")).isEmpty();
    }

    @Test
    void resolveDownloadUrl_unknownArtifact_returnsEmpty() {
        when(repository.findByRunIdAndName(RUN_ID, "missing")).thenReturn(Optional.empty());

        assertThat(service.resolveDownloadUrl(RUN_ID, "missing")).isEmpty();
    }

    @Test
    void resolveUploadedArtifacts_returnsNameToUrlMap() {
        WorkflowArtifact a1 = new WorkflowArtifact();
        a1.setName("report");
        a1.setGcsPath("workflow-artifacts/proj-1/run-1/report");
        WorkflowArtifact a2 = new WorkflowArtifact();
        a2.setName("summary");
        a2.setGcsPath("workflow-artifacts/proj-1/run-1/summary");
        when(repository.findByRunIdAndJobIdAndStatus(RUN_ID, "build", WorkflowArtifactStatus.UPLOADED))
                .thenReturn(List.of(a1, a2));
        when(storageService.generateSignedUrl("workflow-artifacts/proj-1/run-1/report", 60)).thenReturn("url-report");
        when(storageService.generateSignedUrl("workflow-artifacts/proj-1/run-1/summary", 60)).thenReturn("url-summary");

        Map<String, String> result = service.resolveUploadedArtifacts(RUN_ID, "build");

        assertThat(result).containsExactly(Map.entry("report", "url-report"), Map.entry("summary", "url-summary"));
    }

    @Test
    void belongsToRun_matchingRun_true() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setRunId(RUN_ID);
        when(repository.findById("artifact-1")).thenReturn(Optional.of(artifact));

        assertThat(service.belongsToRun("artifact-1", RUN_ID)).isTrue();
        assertThat(service.belongsToRun("artifact-1", "other-run")).isFalse();
    }

    @Test
    void uploadContentPassthrough_streamsBytesToStorage() {
        WorkflowArtifact artifact = new WorkflowArtifact();
        artifact.setGcsPath("workflow-artifacts/proj-1/run-1/report");
        artifact.setContentType("application/json");
        when(repository.findById("artifact-1")).thenReturn(Optional.of(artifact));

        byte[] content = "{\"ok\":true}".getBytes();
        service.uploadContentPassthrough("artifact-1", content);

        verify(storageService).upload("workflow-artifacts/proj-1/run-1/report", content, "application/json");
    }
}
