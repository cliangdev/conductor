package com.conductor.memory;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.User;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed: exercises the real {@link MemoryMaintenanceScheduler} sweep against a live Postgres --
 * consolidation's provider-resolution short-circuit and the retention passes' SQL predicates aren't
 * meaningfully testable against mocks. Own private {@code @Container} plus {@code @TestPropertySource}
 * to flip {@code conductor.memory.maintenance.enabled} back on for just this context, same reasoning as
 * {@code KnowledgeIngestSchedulerIntegrationTest} -- see {@code src/test/resources/application.properties}
 * for why it defaults off (a live nightly tick must never fire against the suite's shared database).
 * Each test uses its own random project, so within this class's own shared private context, tests don't
 * interfere with each other either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@TestPropertySource(properties = "conductor.memory.maintenance.enabled=true")
@Testcontainers
class MemoryMaintenanceSchedulerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private MemoryMaintenanceScheduler scheduler;
    @Autowired
    private AgentMemoryRepository memoryRepository;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectSettingsRepository projectSettingsRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newProject() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Memory Maintenance Test Project");
        project.setKey("MM" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        String projectId = projectRepository.save(project).getId();

        ProjectSettings settings = new ProjectSettings();
        settings.setProjectId(projectId);
        settings.setKnowledgeEnabled(false);
        projectSettingsRepository.save(settings);

        return projectId;
    }

    private AgentMemory reload(String id) {
        return memoryRepository.findById(id).orElseThrow();
    }

    // ---- retention: close ----

    @Test
    void staleLowImportanceLiveRow_getsClosed() {
        String projectId = newProject();
        AgentMemory memory = memoryService.createManual(projectId, "low importance stale fact", MemoryType.FACT, 2);
        memory.setLastAccessedAt(OffsetDateTime.now().minusDays(120));
        memoryRepository.save(memory);

        scheduler.run();

        AgentMemory reloaded = reload(memory.getId());
        assertThat(reloaded.getValidTo()).isNotNull();
        assertThat(reloaded.getSupersededBy()).isNull(); // aged out, not superseded by anything
    }

    @Test
    void highImportanceOrRecentlyAccessedRows_areUntouchedByCloseSweep() {
        String projectId = newProject();

        AgentMemory highImportance = memoryService.createManual(
                projectId, "high importance old fact", MemoryType.FACT, 9);
        highImportance.setLastAccessedAt(OffsetDateTime.now().minusDays(120));
        memoryRepository.save(highImportance);

        AgentMemory recentlyAccessed = memoryService.createManual(
                projectId, "low importance recent fact", MemoryType.FACT, 1);
        recentlyAccessed.setLastAccessedAt(OffsetDateTime.now());
        memoryRepository.save(recentlyAccessed);

        scheduler.run();

        assertThat(reload(highImportance.getId()).getValidTo()).isNull();
        assertThat(reload(recentlyAccessed.getId()).getValidTo()).isNull();
    }

    // ---- retention: purge ----

    @Test
    void longClosedRow_getsPurged() {
        String projectId = newProject();
        AgentMemory memory = memoryService.createManual(projectId, "long since closed", MemoryType.FACT, 5);
        memory.setValidTo(OffsetDateTime.now().minusDays(120));
        memoryRepository.save(memory);
        String id = memory.getId();

        scheduler.run();

        assertThat(memoryRepository.findById(id)).isEmpty();
    }

    @Test
    void recentlyClosedRow_isNotYetPurged() {
        String projectId = newProject();
        AgentMemory memory = memoryService.createManual(projectId, "recently closed", MemoryType.FACT, 5);
        memory.setValidTo(OffsetDateTime.now().minusDays(5));
        memoryRepository.save(memory);
        String id = memory.getId();

        scheduler.run();

        assertThat(memoryRepository.findById(id)).isPresent();
    }

    // ---- consolidation pass ----

    @Test
    void consolidationWithNoResolvableProviderKey_isCleanNoOp() {
        String projectId = newProject(); // no CEO agent provisioned -- resolveProvider must return null
        AgentMemory raw = memoryService.createRaw(projectId, null, null,
                "raw memory with no ceo credential in this project", MemoryType.FACT, 5);
        // @PrePersist unconditionally stamps createdAt at insert time, so backdating past the
        // consolidation min-age window (default 24h) has to go around JPA -- same trick as
        // KnowledgeIngestSchedulerIntegrationTest's started_at backdate.
        jdbcTemplate.update("UPDATE agent_memories SET created_at = ? WHERE id = ?",
                Timestamp.from(OffsetDateTime.now().minusHours(48).toInstant()), raw.getId());

        scheduler.run();

        AgentMemory reloaded = reload(raw.getId());
        assertThat(reloaded.getStatus()).isEqualTo(MemoryStatus.RAW);
        assertThat(reloaded.getConsolidationAttempts()).isZero();
    }
}
