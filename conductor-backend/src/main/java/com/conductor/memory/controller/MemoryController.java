package com.conductor.memory.controller;

import com.conductor.exception.BusinessException;
import com.conductor.generated.api.MemoryApi;
import com.conductor.generated.model.MemoryCounts;
import com.conductor.generated.model.MemoryCreateRequest;
import com.conductor.generated.model.MemoryDetailView;
import com.conductor.generated.model.MemoryListResponse;
import com.conductor.generated.model.MemoryStatus;
import com.conductor.generated.model.MemoryType;
import com.conductor.generated.model.MemoryUpdateRequest;
import com.conductor.generated.model.MemoryView;
import com.conductor.memory.AgentMemory;
import com.conductor.memory.MemoryService;
import com.conductor.service.ProjectSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * External {@code /api/v1} surface for agent memory -- read/write access to the same {@code
 * agent_memories} rows the ReAct loop reads via {@code DatabaseMemoryAugmentor} and writes via {@code
 * MemoryExtractionService}/{@code MemoryConsolidationService}. This controller is purely a UI/API lens
 * over {@link MemoryService}; it never runs extraction or consolidation itself.
 */
@RestController
public class MemoryController implements MemoryApi {

    private final MemoryService memoryService;
    private final ProjectSecurityService projectSecurityService;

    public MemoryController(MemoryService memoryService, ProjectSecurityService projectSecurityService) {
        this.memoryService = memoryService;
        this.projectSecurityService = projectSecurityService;
    }

    /** Wire values for the view's derived {@code MemoryStatus} tri-state (raw/active/superseded). */
    private static final Set<String> VALID_STATUS_FILTERS = Set.of("raw", "active", "superseded");

    @Override
    public ResponseEntity<MemoryListResponse> listMemories(String projectId, String q, String status,
                                                             String type, String agentId, Integer limit,
                                                             Integer offset) {
        projectSecurityService.requireProjectAccess(projectId);
        MemoryService.MemoryListResult result = memoryService.list(projectId, validateStatusFilter(status),
                parseTypeFilter(type), agentId, q, limit, offset);

        MemoryListResponse response = new MemoryListResponse();
        response.setItems(result.items().stream().map(this::toView).toList());
        response.setTotal(result.total());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<MemoryCounts> getMemoryCounts(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toDto(memoryService.counts(projectId)));
    }

    @Override
    public ResponseEntity<MemoryView> createMemory(String projectId, MemoryCreateRequest request) {
        projectSecurityService.requireProjectAccess(projectId);
        int importance = request.getImportance() != null ? request.getImportance() : 5;
        AgentMemory memory = memoryService.createManual(projectId, request.getContent(),
                toDomainType(request.getType()), importance);
        return ResponseEntity.status(201).body(toView(memory));
    }

    @Override
    public ResponseEntity<MemoryDetailView> getMemory(String projectId, String memoryId) {
        projectSecurityService.requireProjectAccess(projectId);
        AgentMemory memory = memoryService.get(projectId, memoryId);
        List<AgentMemory> history = memoryService.history(projectId, memoryId);
        return ResponseEntity.ok(toDetailView(memory, history));
    }

    @Override
    public ResponseEntity<MemoryView> updateMemory(String projectId, String memoryId, MemoryUpdateRequest request) {
        projectSecurityService.requireProjectAccess(projectId);
        AgentMemory memory = memoryService.update(projectId, memoryId, request.getContent(),
                toDomainType(request.getType()), request.getImportance());
        return ResponseEntity.ok(toView(memory));
    }

    @Override
    public ResponseEntity<Void> deleteMemory(String projectId, String memoryId) {
        projectSecurityService.requireProjectAccess(projectId);
        memoryService.delete(projectId, memoryId);
        return ResponseEntity.noContent().build();
    }

    // ---- DTO <-> domain mapping ----

    /**
     * {@code listMemories}' {@code status}/{@code type} filters are plain strings in the spec rather than
     * {@code $ref}'d enums -- Spring's default query-param binder converts via {@code Enum.valueOf}, which
     * is case-sensitive and would 500 on every legitimate lowercase wire value (e.g. {@code active}) since
     * this API's enums serialize lowercase. Request/response *bodies* don't have this problem (Jackson
     * binds enums via {@code @JsonCreator}/{@code fromValue}), so {@link MemoryCreateRequest}/{@link
     * MemoryUpdateRequest}/{@link MemoryView} keep proper enum types.
     */
    private String validateStatusFilter(String status) {
        if (status != null && !VALID_STATUS_FILTERS.contains(status)) {
            throw new BusinessException("Invalid status filter: " + status);
        }
        return status;
    }

    private com.conductor.memory.MemoryType parseTypeFilter(String type) {
        if (type == null) {
            return null;
        }
        try {
            return com.conductor.memory.MemoryType.valueOf(MemoryType.fromValue(type).name());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid type filter: " + type);
        }
    }

    private com.conductor.memory.MemoryType toDomainType(MemoryType type) {
        return type != null ? com.conductor.memory.MemoryType.valueOf(type.name()) : null;
    }

    /** The view's derived tri-state -- a closed row (validTo set) always reports SUPERSEDED, regardless
     *  of which of RAW/ACTIVE the stored entity status carries. */
    private MemoryStatus deriveStatus(AgentMemory m) {
        if (m.getValidTo() != null) {
            return MemoryStatus.SUPERSEDED;
        }
        return m.getStatus() == com.conductor.memory.MemoryStatus.RAW ? MemoryStatus.RAW : MemoryStatus.ACTIVE;
    }

    private MemoryView toView(AgentMemory m) {
        MemoryView dto = new MemoryView();
        dto.setId(m.getId());
        dto.setContent(m.getContent());
        dto.setType(MemoryType.valueOf(m.getMemoryType().name()));
        dto.setStatus(deriveStatus(m));
        dto.setImportance(m.getImportance());
        dto.setAgentId(m.getAgentId());
        dto.setSourceConversationId(m.getSourceConversationId());
        dto.setValidFrom(m.getValidFrom());
        dto.setValidTo(m.getValidTo());
        dto.setSupersededBy(m.getSupersededBy());
        dto.setPromotedAt(m.getPromotedAt());
        dto.setAccessCount(m.getAccessCount());
        dto.setLastAccessedAt(m.getLastAccessedAt());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }

    private MemoryDetailView toDetailView(AgentMemory m, List<AgentMemory> history) {
        MemoryDetailView dto = new MemoryDetailView();
        dto.setId(m.getId());
        dto.setContent(m.getContent());
        dto.setType(MemoryType.valueOf(m.getMemoryType().name()));
        dto.setStatus(deriveStatus(m));
        dto.setImportance(m.getImportance());
        dto.setAgentId(m.getAgentId());
        dto.setSourceConversationId(m.getSourceConversationId());
        dto.setValidFrom(m.getValidFrom());
        dto.setValidTo(m.getValidTo());
        dto.setSupersededBy(m.getSupersededBy());
        dto.setPromotedAt(m.getPromotedAt());
        dto.setAccessCount(m.getAccessCount());
        dto.setLastAccessedAt(m.getLastAccessedAt());
        dto.setCreatedAt(m.getCreatedAt());
        dto.setHistory(history.stream().map(this::toView).toList());
        return dto;
    }

    private MemoryCounts toDto(MemoryService.MemoryCounts counts) {
        MemoryCounts dto = new MemoryCounts();
        dto.setLiveTotal(counts.liveTotal());
        dto.setRaw(counts.raw());
        dto.setConsolidated(counts.consolidated());
        dto.setSuperseded(counts.superseded());
        return dto;
    }
}
