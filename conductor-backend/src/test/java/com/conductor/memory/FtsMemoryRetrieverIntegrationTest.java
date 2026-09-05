package com.conductor.memory;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.memory.MemoryRetriever.ScoredMemory;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DB-backed test for {@link FtsMemoryRetriever} -- full-text search blended with the importance/recency floor. */
@Transactional
class FtsMemoryRetrieverIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private AgentMemoryRepository memoryRepository;
    @Autowired
    private FtsMemoryRetriever retriever;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Memory Retriever Test Project");
        project.setKey("MR" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    private AgentMemory memory(String content, int importance, OffsetDateTime validFrom, OffsetDateTime validTo) {
        AgentMemory memory = new AgentMemory();
        memory.setProjectId(projectId);
        memory.setMemoryType(MemoryType.FACT);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setContent(content);
        memory.setImportance(importance);
        if (validFrom != null) {
            memory.setValidFrom(validFrom);
        }
        memory.setValidTo(validTo);
        return memoryRepository.save(memory);
    }

    @Test
    void matchesOnRealisticMultiwordQuery() {
        memory("The deployment pipeline for the backend service runs through Cloud Run staging.", 5,
                OffsetDateTime.now(), null);
        memory("Tuesday is taco day in the office kitchen.", 5, OffsetDateTime.now(), null);
        memoryRepository.flush();

        List<ScoredMemory> hits = retriever.retrieve(projectId, "How does the backend deployment pipeline work?", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).memory().getContent()).contains("deployment pipeline");
    }

    @Test
    void excludesClosedRows() {
        memory("Uniquely mentions frobnication gigawatt widgets here.", 5,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now());
        memoryRepository.flush();

        List<ScoredMemory> hits = retriever.retrieve(projectId, "frobnication gigawatt widgets", 10);

        assertThat(hits).noneMatch(hit -> hit.memory().getContent().contains("frobnication"));
    }

    @Test
    void floorPoolIncludesHighImportanceNonMatchingRow() {
        AgentMemory important = memory("A completely unrelated but very important standing decision.", 10,
                OffsetDateTime.now(), null);
        memory("Some other low importance note about spaceships and rockets.", 1, OffsetDateTime.now(), null);
        memoryRepository.flush();

        List<ScoredMemory> hits = retriever.retrieve(projectId, "spaceships rockets launch", 10);

        assertThat(hits).anyMatch(hit -> hit.memory().getId().equals(important.getId()));
    }

    @Test
    void scoringOrdersMatchAboveFloorOnlyCandidate() {
        AgentMemory matching = memory("The quarterly roadmap review happens every March in the planning cycle.", 3,
                OffsetDateTime.now(), null);
        memory("Floor-only candidate about unrelated topics entirely.", 3, OffsetDateTime.now().minusDays(30), null);
        memoryRepository.flush();

        List<ScoredMemory> hits = retriever.retrieve(projectId, "quarterly roadmap review planning cycle", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).memory().getId()).isEqualTo(matching.getId());
        assertThat(hits.get(0).relevance()).isGreaterThan(0.0);
    }
}
