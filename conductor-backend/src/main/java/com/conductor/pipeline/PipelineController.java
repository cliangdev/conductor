package com.conductor.pipeline;

import com.conductor.generated.api.PipelineApi;
import com.conductor.generated.model.PipelineHealthDto;
import com.conductor.generated.model.PipelineStage;
import com.conductor.generated.model.PipelineStageEdge;
import com.conductor.generated.model.PipelineStageHealth;
import com.conductor.generated.model.PipelineTraceDto;
import com.conductor.generated.model.PipelineTraceNode;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * External {@code /api/v1} surface for the pipeline observability view (issue #342): read-only,
 * membership-gated like the rest of the knowledge surface, no new mutation endpoints. A distinct
 * {@code pipeline} spec tag (rather than folding into {@code knowledge}) since it reads across the
 * webhook/connector/knowledge/workflow bounded contexts, not just one -- see
 * {@code PipelineHealthService}/{@code PipelineTraceService}.
 */
@RestController
public class PipelineController implements PipelineApi {

    private final PipelineHealthService healthService;
    private final PipelineTraceService traceService;
    private final ProjectSecurityService projectSecurityService;

    public PipelineController(PipelineHealthService healthService,
                               PipelineTraceService traceService,
                               ProjectSecurityService projectSecurityService) {
        this.healthService = healthService;
        this.traceService = traceService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<PipelineHealthDto> getPipelineHealth(String projectId) {
        requireMember(projectId);
        List<PipelineStageHealth> stages = healthService.getHealth(projectId).stream()
                .map(this::toDto)
                .toList();
        List<PipelineStageEdge> edges = healthService.getTopology().stream()
                .map(this::toDto)
                .toList();
        PipelineHealthDto dto = new PipelineHealthDto();
        dto.setStages(stages);
        dto.setEdges(edges);
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<PipelineTraceDto> getPipelineTrace(String projectId, String pageId, String sourceId,
                                                              String feedId, String webhookEventId) {
        requireMember(projectId);
        List<PipelineTraceNode> nodes = traceService.trace(projectId, pageId, sourceId, feedId, webhookEventId)
                .stream()
                .map(this::toDto)
                .toList();
        PipelineTraceDto dto = new PipelineTraceDto();
        dto.setNodes(nodes);
        return ResponseEntity.ok(dto);
    }

    // ---- auth ----

    /** Member-level gate — see {@link ProjectSecurityService#requireProjectAccess} for who passes. */
    private void requireMember(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
    }

    // ---- DTO mapping ----

    private PipelineStageHealth toDto(PipelineStageHealthView view) {
        return new PipelineStageHealth(PipelineStage.fromValue(view.stage()), view.label(), view.counts());
    }

    private PipelineStageEdge toDto(PipelineTopology.Edge edge) {
        return new PipelineStageEdge(PipelineStage.fromValue(edge.from()), PipelineStage.fromValue(edge.to()));
    }

    private PipelineTraceNode toDto(PipelineTraceNodeView view) {
        PipelineTraceNode dto = new PipelineTraceNode(
                PipelineStage.fromValue(view.stage()), view.id(), view.degraded());
        dto.setStatus(view.status());
        dto.setOccurredAt(view.occurredAt());
        dto.setLabel(view.label());
        dto.setLink(view.link());
        return dto;
    }
}
