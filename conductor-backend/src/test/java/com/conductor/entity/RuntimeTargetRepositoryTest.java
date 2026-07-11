package com.conductor.entity;

import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.RuntimeTargetRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class RuntimeTargetRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private RuntimeTargetRepository runtimeTargetRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private Project project;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Test Project");
        project.setKey("RTT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);
    }

    private RuntimeTarget newTarget(String name) {
        RuntimeTarget target = new RuntimeTarget();
        target.setProjectId(project.getId());
        target.setName(name);
        target.setProvider("gcp-cloud-run");
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        target.setConfigJson("{\"gcpProjectId\":\"customer-proj\",\"region\":\"us-central1\","
                + "\"jobName\":\"conductor-" + name + "\",\"image\":\"img:1\"}");
        return target;
    }

    @Test
    void persistsWithDefaultsAndTimestamps() {
        RuntimeTarget saved = runtimeTargetRepository.saveAndFlush(newTarget("my-target"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(RuntimeTargetStatus.PROVISIONING);
    }

    @Test
    void findByProjectIdReturnsAllTargetsForProject() {
        runtimeTargetRepository.save(newTarget("target-a"));
        runtimeTargetRepository.save(newTarget("target-b"));

        List<RuntimeTarget> found = runtimeTargetRepository.findByProjectId(project.getId());

        assertThat(found).hasSize(2).extracting(RuntimeTarget::getName)
                .containsExactlyInAnyOrder("target-a", "target-b");
    }

    @Test
    void findByProjectIdAndNameReturnsMatch() {
        runtimeTargetRepository.save(newTarget("my-target"));

        Optional<RuntimeTarget> found = runtimeTargetRepository.findByProjectIdAndName(project.getId(), "my-target");

        assertThat(found).isPresent();
        assertThat(found.get().getProvider()).isEqualTo("gcp-cloud-run");
    }

    @Test
    void findByProjectIdAndNameReturnsEmptyForOtherProject() {
        runtimeTargetRepository.save(newTarget("my-target"));

        assertThat(runtimeTargetRepository.findByProjectIdAndName("some-other-project", "my-target")).isEmpty();
    }

    @Test
    void existsByProjectIdAndNameReflectsPresence() {
        assertThat(runtimeTargetRepository.existsByProjectIdAndName(project.getId(), "my-target")).isFalse();

        runtimeTargetRepository.save(newTarget("my-target"));

        assertThat(runtimeTargetRepository.existsByProjectIdAndName(project.getId(), "my-target")).isTrue();
    }

    @Test
    void uniqueConstraintRejectsDuplicateNameInSameProject() {
        runtimeTargetRepository.saveAndFlush(newTarget("my-target"));

        assertThatThrownBy(() -> runtimeTargetRepository.saveAndFlush(newTarget("my-target")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void connectionIdIsSetNullOnConnectionDelete() {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("gcp");
        connection.setAuthType("SERVICE_ACCOUNT");
        connectionRepository.saveAndFlush(connection);

        RuntimeTarget target = newTarget("my-target");
        target.setConnectionId(connection.getId());
        RuntimeTarget saved = runtimeTargetRepository.saveAndFlush(target);

        connectionRepository.delete(connection);
        connectionRepository.flush();
        // The FK's ON DELETE SET NULL runs at the database level — clear the persistence context so
        // the re-fetch below hits the DB instead of returning the stale managed instance.
        entityManager.clear();

        RuntimeTarget reloaded = runtimeTargetRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getConnectionId()).isNull();
    }

    @Test
    void projectFkRequiresExistingProject() {
        RuntimeTarget orphan = newTarget("my-target");
        orphan.setProjectId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> runtimeTargetRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
