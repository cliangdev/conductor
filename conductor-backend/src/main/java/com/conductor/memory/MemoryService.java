package com.conductor.memory;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle operations over {@link AgentMemory}. Every method scopes by {@code projectId} -- a row
 * belonging to another project is treated identically to one that doesn't exist, so a caller can never
 * distinguish "doesn't exist" from "exists in someone else's project" by probing ids (same contract as
 * {@code ConversationNotFoundException}). Not-found uses the repo-wide convention of throwing
 * {@link EntityNotFoundException} directly rather than a bespoke type -- {@code GlobalExceptionHandler}
 * already maps it to 404.
 */
@Service
public class MemoryService {

    private static final int MAX_HISTORY_DEPTH = 10;

    private final AgentMemoryRepository repository;

    public MemoryService(AgentMemoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentMemory createManual(String projectId, String content, MemoryType type, int importance) {
        AgentMemory memory = new AgentMemory();
        memory.setProjectId(projectId);
        memory.setMemoryType(type);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setContent(content);
        memory.setImportance(importance);
        return repository.save(memory);
    }

    @Transactional
    public AgentMemory createRaw(String projectId, String agentId, String sourceConversationId, String content,
                                  MemoryType type, int importance) {
        AgentMemory memory = new AgentMemory();
        memory.setProjectId(projectId);
        memory.setAgentId(agentId);
        memory.setSourceConversationId(sourceConversationId);
        memory.setMemoryType(type);
        memory.setStatus(MemoryStatus.RAW);
        memory.setContent(content);
        memory.setImportance(importance);
        return repository.save(memory);
    }

    /** Partial update. Rejects with {@link MemoryConflictException} if the row's validity window is closed. */
    @Transactional
    public AgentMemory update(String projectId, String id, String content, MemoryType type, Integer importance) {
        AgentMemory memory = get(projectId, id);
        if (memory.getValidTo() != null) {
            throw new MemoryConflictException("Cannot update a closed memory: " + id);
        }
        if (content != null) {
            memory.setContent(content);
        }
        if (type != null) {
            memory.setMemoryType(type);
        }
        if (importance != null) {
            memory.setImportance(importance);
        }
        return repository.save(memory);
    }

    /**
     * Closes {@code oldId} and inserts its ACTIVE replacement in one transaction: {@code oldId.validTo}
     * is set and {@code oldId.supersededBy} points at the new row's id.
     */
    @Transactional
    public AgentMemory supersede(String projectId, String oldId, String newContent, MemoryType newType, int newImportance) {
        AgentMemory old = get(projectId, oldId);
        if (old.getValidTo() != null) {
            throw new MemoryConflictException("Memory already superseded: " + oldId);
        }

        AgentMemory replacement = new AgentMemory();
        replacement.setProjectId(projectId);
        replacement.setAgentId(old.getAgentId());
        replacement.setSourceConversationId(old.getSourceConversationId());
        replacement.setMemoryType(newType);
        replacement.setStatus(MemoryStatus.ACTIVE);
        replacement.setContent(newContent);
        replacement.setImportance(newImportance);
        AgentMemory saved = repository.save(replacement);

        old.setValidTo(OffsetDateTime.now());
        old.setSupersededBy(saved.getId());
        repository.save(old);

        return saved;
    }

    /**
     * Closes {@code targetId} and points its {@code supersededBy} at {@code replacementId} -- unlike
     * {@link #supersede}, the replacement row already exists (it isn't created here). Used by {@code
     * MemoryConsolidationService}'s SUPERSEDE decision, where the raw row being consolidated *is* the
     * replacement: bending {@link #supersede} to accept an existing replacement id would blur its
     * "always inserts a fresh row" contract, so this is a separate, narrower operation instead.
     */
    @Transactional
    public void closeAndLink(String projectId, String targetId, String replacementId) {
        AgentMemory target = get(projectId, targetId);
        if (target.getValidTo() != null) {
            throw new MemoryConflictException("Memory already superseded: " + targetId);
        }
        target.setValidTo(OffsetDateTime.now());
        target.setSupersededBy(replacementId);
        repository.save(target);
    }

    @Transactional
    public void delete(String projectId, String id) {
        repository.delete(get(projectId, id));
    }

    @Transactional(readOnly = true)
    public AgentMemory get(String projectId, String id) {
        return repository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Memory not found: " + id));
    }

    @Transactional(readOnly = true)
    public MemoryListResult list(String projectId, String status, MemoryType type, String agentId, String q,
                                  int limit, int offset) {
        String typeValue = type != null ? type.name() : null;
        List<AgentMemory> items = repository.listForUi(projectId, status, typeValue, agentId, q, limit, offset);
        long total = repository.countForList(projectId, status, typeValue, agentId, q);
        return new MemoryListResult(items, total);
    }

    @Transactional(readOnly = true)
    public MemoryCounts counts(String projectId) {
        long raw = repository.countByProjectIdAndStatusAndValidToIsNull(projectId, MemoryStatus.RAW);
        long active = repository.countByProjectIdAndStatusAndValidToIsNull(projectId, MemoryStatus.ACTIVE);
        long superseded = repository.countByProjectIdAndValidToIsNotNull(projectId);
        return new MemoryCounts(raw + active, raw, active, superseded);
    }

    /**
     * The supersession chain ancestors of {@code id}, most recent first, depth-capped at
     * {@value #MAX_HISTORY_DEPTH}. The old row carries {@code supersededBy} pointing at the new row, so
     * the ancestor of X is the row whose {@code supersededBy = X.id}.
     */
    @Transactional(readOnly = true)
    public List<AgentMemory> history(String projectId, String id) {
        List<AgentMemory> chain = new ArrayList<>();
        String cursorId = get(projectId, id).getId();
        for (int i = 0; i < MAX_HISTORY_DEPTH; i++) {
            var ancestor = repository.findByProjectIdAndSupersededBy(projectId, cursorId);
            if (ancestor.isEmpty()) {
                break;
            }
            chain.add(ancestor.get());
            cursorId = ancestor.get().getId();
        }
        return chain;
    }

    public record MemoryListResult(List<AgentMemory> items, long total) {
    }

    public record MemoryCounts(long liveTotal, long raw, long consolidated, long superseded) {
    }
}
