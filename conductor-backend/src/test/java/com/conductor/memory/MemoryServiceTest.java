package com.conductor.memory;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB-backed test for {@link MemoryService}'s lifecycle operations. */
@Transactional
class MemoryServiceTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private MemoryService memoryService;
    @Autowired
    private AgentMemoryRepository memoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private String projectId;
    private String otherProjectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        projectId = createProject(user).getId();
        otherProjectId = createProject(user).getId();
    }

    private Project createProject(User user) {
        Project project = new Project();
        project.setName("Memory Service Test Project");
        project.setKey("MS" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        return projectRepository.save(project);
    }

    @Test
    void createManualProducesActiveRow() {
        AgentMemory memory = memoryService.createManual(projectId, "The team prefers async standups.",
                MemoryType.PREFERENCE, 7);

        assertThat(memory.getId()).isNotBlank();
        assertThat(memory.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(memory.getValidTo()).isNull();
        assertThat(memory.getAgentId()).isNull();
    }

    @Test
    void createRawProducesRawRowAttributedToAgent() {
        AgentMemory memory = memoryService.createRaw(projectId, "agent-123", "conv-456",
                "User mentioned they like dark mode.", MemoryType.FACT, 4);

        assertThat(memory.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(memory.getAgentId()).isEqualTo("agent-123");
        assertThat(memory.getSourceConversationId()).isEqualTo("conv-456");
    }

    @Test
    void supersedeClosesOldRowAndReturnsNewActiveRow() {
        AgentMemory original = memoryService.createManual(projectId, "Ships on Fridays.", MemoryType.DECISION, 5);

        AgentMemory replacement = memoryService.supersede(projectId, original.getId(),
                "Ships on Tuesdays now.", MemoryType.DECISION, 6);

        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(replacement.getValidTo()).isNull();
        assertThat(replacement.getContent()).isEqualTo("Ships on Tuesdays now.");

        AgentMemory reloaded = memoryRepository.findById(original.getId()).orElseThrow();
        assertThat(reloaded.getValidTo()).isNotNull();
        assertThat(reloaded.getSupersededBy()).isEqualTo(replacement.getId());
    }

    @Test
    void supersedeOnAlreadyClosedRowThrowsConflict() {
        AgentMemory original = memoryService.createManual(projectId, "Ships on Fridays.", MemoryType.DECISION, 5);
        memoryService.supersede(projectId, original.getId(), "Ships on Tuesdays.", MemoryType.DECISION, 5);

        assertThatThrownBy(() -> memoryService.supersede(projectId, original.getId(), "Ships on Mondays.",
                MemoryType.DECISION, 5))
                .isInstanceOf(MemoryConflictException.class)
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void historyWalksSupersessionChainMostRecentFirst() {
        AgentMemory v1 = memoryService.createManual(projectId, "v1", MemoryType.DECISION, 5);
        AgentMemory v2 = memoryService.supersede(projectId, v1.getId(), "v2", MemoryType.DECISION, 5);
        AgentMemory v3 = memoryService.supersede(projectId, v2.getId(), "v3", MemoryType.DECISION, 5);

        List<AgentMemory> history = memoryService.history(projectId, v3.getId());

        assertThat(history).extracting(AgentMemory::getContent).containsExactly("v2", "v1");
    }

    @Test
    void countsReflectRawActiveAndSupersededBuckets() {
        memoryService.createRaw(projectId, null, null, "raw one", MemoryType.FACT, 5);
        AgentMemory active = memoryService.createManual(projectId, "active one", MemoryType.FACT, 5);
        memoryService.supersede(projectId, active.getId(), "active two", MemoryType.FACT, 5);

        MemoryService.MemoryCounts counts = memoryService.counts(projectId);

        assertThat(counts.raw()).isEqualTo(1);
        assertThat(counts.consolidated()).isEqualTo(1);
        assertThat(counts.superseded()).isEqualTo(1);
        assertThat(counts.liveTotal()).isEqualTo(2);
    }

    @Test
    void crossProjectAccessThrowsNotFound() {
        AgentMemory memory = memoryService.createManual(projectId, "scoped to projectId", MemoryType.FACT, 5);

        assertThatThrownBy(() -> memoryService.get(otherProjectId, memory.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void updateOnClosedRowIsRejected() {
        AgentMemory original = memoryService.createManual(projectId, "will be closed", MemoryType.FACT, 5);
        memoryService.closeValidity(projectId, original.getId());

        assertThatThrownBy(() -> memoryService.update(projectId, original.getId(), "new content", null, null))
                .isInstanceOf(MemoryConflictException.class);
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        AgentMemory original = memoryService.createManual(projectId, "original content", MemoryType.FACT, 5);

        AgentMemory updated = memoryService.update(projectId, original.getId(), null, null, 9);

        assertThat(updated.getContent()).isEqualTo("original content");
        assertThat(updated.getImportance()).isEqualTo(9);
    }

    @Test
    void deleteHardRemovesRow() {
        AgentMemory memory = memoryService.createManual(projectId, "to delete", MemoryType.FACT, 5);

        memoryService.delete(projectId, memory.getId());

        assertThat(memoryRepository.findById(memory.getId())).isEmpty();
    }

    @Test
    void listFiltersByStatusAndSupportsPaging() {
        memoryService.createRaw(projectId, null, null, "raw memory about widgets", MemoryType.FACT, 5);
        memoryService.createManual(projectId, "active memory about widgets", MemoryType.FACT, 5);
        memoryRepository.flush();

        MemoryService.MemoryListResult rawOnly = memoryService.list(projectId, "raw", null, null, null, 10, 0);
        assertThat(rawOnly.items()).hasSize(1);
        assertThat(rawOnly.items().get(0).getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(rawOnly.total()).isEqualTo(1);

        MemoryService.MemoryListResult all = memoryService.list(projectId, null, null, null, null, 10, 0);
        assertThat(all.total()).isEqualTo(2);
    }
}
