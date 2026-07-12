package com.conductor.internal;

import com.conductor.service.WorkflowArtifactService;
import com.conductor.workflow.RunTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-profile-only passthrough for uploading a workflow artifact's raw bytes, used when
 * {@code StorageService} can't mint a signed upload URL (see {@code LocalStorageService}). Not part of
 * the generated {@code WorkflowInternalApi} — it needs a raw binary request body, which the generated
 * JSON-only interface doesn't model; see {@code LocalFileController} for the same
 * generated-interface-free pattern used for local file reads. Bare mapping: {@code ApiPathConfig}
 * prefixes every {@code com.conductor.internal} controller with {@code /internal/v1}, and
 * {@code /internal/**} is {@code permitAll} in security config — this controller does its own runToken
 * check instead, same as {@link WorkflowInternalCallbackController}.
 *
 * <p>No equivalent GET passthrough exists: local downloads reuse {@code StorageService#generateSignedUrl},
 * which for the local profile resolves to the already-permitAll {@code /api/v1/local-files/**} static
 * file endpoint — see {@code WorkflowArtifactService}'s javadoc.
 */
@RestController
public class WorkflowArtifactContentController {

    private final RunTokenService runTokenService;
    private final WorkflowArtifactService artifactService;

    public WorkflowArtifactContentController(RunTokenService runTokenService, WorkflowArtifactService artifactService) {
        this.runTokenService = runTokenService;
        this.artifactService = artifactService;
    }

    @PutMapping("/workflow-runs/{runId}/artifacts/{artifactId}/content")
    public ResponseEntity<Void> uploadContent(@PathVariable String runId, @PathVariable String artifactId,
                                               @RequestBody byte[] content, jakarta.servlet.http.HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")
                || !runTokenService.validateRunToken(authHeader.substring(7), runId)) {
            return ResponseEntity.status(401).build();
        }
        if (!artifactService.belongsToRun(artifactId, runId)) {
            return ResponseEntity.notFound().build();
        }
        artifactService.uploadContentPassthrough(artifactId, content);
        return ResponseEntity.ok().build();
    }
}
