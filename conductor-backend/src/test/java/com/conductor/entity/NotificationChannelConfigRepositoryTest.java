package com.conductor.entity;

import com.conductor.notification.EventType;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationChannelConfigRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
@Transactional
class NotificationChannelConfigRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private NotificationChannelConfigRepository repository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private String projectId1;
    private String projectId2;
    private String projectId3;
    private String projectId4;
    private String projectId999;

    @BeforeEach
    void setUpProjects() {
        User user = new User();
        user.setFirebaseUid("test-firebase-uid-" + UUID.randomUUID());
        user.setEmail("test@example.com");
        userRepository.save(user);

        projectId1 = createProject(user, "Project 1");
        projectId2 = createProject(user, "Project 2");
        projectId3 = createProject(user, "Project 3");
        projectId4 = createProject(user, "Project 4");
        projectId999 = createProject(user, "Project 999");
    }

    private String createProject(User owner, String name) {
        Project project = new Project();
        project.setName(name);
        project.setCreatedBy(owner);
        return projectRepository.save(project).getId();
    }

    private NotificationChannelConfig buildConfig(String projectId, EventType eventType, String webhookUrl) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setProjectId(projectId);
        config.setEventType(eventType);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl(webhookUrl);
        config.setEnabled(true);
        return config;
    }

    @Test
    void saveAndRetrieveByProjectIdAndEventType() {
        NotificationChannelConfig config = buildConfig(projectId1, EventType.ISSUE_SUBMITTED, "https://discord.com/webhook/1");
        repository.save(config);

        Optional<NotificationChannelConfig> found = repository.findByProjectIdAndEventType(projectId1, EventType.ISSUE_SUBMITTED);

        assertThat(found).isPresent();
        assertThat(found.get().getProjectId()).isEqualTo(projectId1);
        assertThat(found.get().getEventType()).isEqualTo(EventType.ISSUE_SUBMITTED);
        assertThat(found.get().getWebhookUrl()).isEqualTo("https://discord.com/webhook/1");
        assertThat(found.get().getProvider()).isEqualTo(ProviderType.DISCORD);
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void uniqueConstraintPreventsDuplicateProjectIdAndEventType() {
        repository.saveAndFlush(buildConfig(projectId2, EventType.ISSUE_APPROVED, "https://discord.com/webhook/2"));

        NotificationChannelConfig duplicate = buildConfig(projectId2, EventType.ISSUE_APPROVED, "https://discord.com/webhook/3");

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByProjectIdReturnsAllConfigsForProject() {
        repository.save(buildConfig(projectId3, EventType.ISSUE_SUBMITTED, "https://discord.com/webhook/4"));
        repository.save(buildConfig(projectId3, EventType.ISSUE_APPROVED, "https://discord.com/webhook/5"));
        repository.save(buildConfig(projectId3, EventType.MEMBER_JOINED, "https://discord.com/webhook/6"));
        repository.save(buildConfig(projectId999, EventType.ISSUE_SUBMITTED, "https://discord.com/webhook/7"));

        List<NotificationChannelConfig> configs = repository.findByProjectId(projectId3);

        assertThat(configs).hasSize(3);
        assertThat(configs).extracting(NotificationChannelConfig::getProjectId).containsOnly(projectId3);
        assertThat(configs).extracting(NotificationChannelConfig::getEventType)
                .containsExactlyInAnyOrder(EventType.ISSUE_SUBMITTED, EventType.ISSUE_APPROVED, EventType.MEMBER_JOINED);
    }

    @Test
    void deleteByProjectIdAndEventTypeRemovesRecord() {
        repository.save(buildConfig(projectId4, EventType.REVIEW_SUBMITTED, "https://discord.com/webhook/8"));
        assertThat(repository.findByProjectIdAndEventType(projectId4, EventType.REVIEW_SUBMITTED)).isPresent();

        repository.deleteByProjectIdAndEventType(projectId4, EventType.REVIEW_SUBMITTED);

        assertThat(repository.findByProjectIdAndEventType(projectId4, EventType.REVIEW_SUBMITTED)).isEmpty();
    }
}
