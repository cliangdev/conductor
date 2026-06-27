package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-level uniqueness for the unified {@code connection} table (V56):
 *   #9 single-instance connectors get at most one row per (project, connector),
 *      and concurrent {@code getOrCreateSingle} converges on exactly one row.
 *   #4 a github installation cannot be double-connected within one project, while
 *      cross-project sharing of the same installationId stays allowed.
 */
class ConnectionServiceUniquenessTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private String projectId;
    private String otherProjectId;

    @BeforeEach
    void setUp() {
        connectionRepository.deleteAll();
        projectId = newProject("Proj A");
        otherProjectId = newProject("Proj B");
    }

    private String newProject(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName(name);
        project.setKey(UUID.randomUUID().toString().substring(0, 8));
        project.setCreatedBy(user);
        return projectRepository.save(project).getId();
    }

    // --- #9 single-instance --------------------------------------------------

    @Test
    void createMarksSingleInstanceConnectorRows() {
        Connection c = connectionService.create(projectId, "posthog", AuthType.API_KEY, "PostHog", null);
        assertThat(c.isSingleInstance()).isTrue();
    }

    @Test
    void createDoesNotMarkMultiInstanceConnectorRows() {
        Connection c = connectionService.create(projectId, "github", AuthType.APP, "repo", null);
        assertThat(c.isSingleInstance()).isFalse();
    }

    @Test
    void secondSingleInstanceInsertViolatesUniqueIndex() {
        connectionService.create(projectId, "posthog", AuthType.API_KEY, "PostHog", null);
        assertThatThrownBy(() ->
                connectionService.create(projectId, "posthog", AuthType.API_KEY, "PostHog dup", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void getOrCreateSingleReturnsExistingRowOnSecondCall() {
        Connection first = connectionService.getOrCreateSingle(projectId, "posthog", AuthType.API_KEY);
        Connection second = connectionService.getOrCreateSingle(projectId, "posthog", AuthType.API_KEY);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(connectionRepository.findByProjectIdAndConnectorId(projectId, "posthog")).hasSize(1);
    }

    @Test
    void concurrentGetOrCreateSingleConvergesOnOneRow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Connection>> tasks = java.util.Collections.nCopies(threads,
                    () -> connectionService.getOrCreateSingle(projectId, "posthog", AuthType.API_KEY));
            List<Future<Connection>> results = pool.invokeAll(tasks);

            String winningId = null;
            for (Future<Connection> f : results) {
                Connection c = f.get(); // must not throw — losers recover by re-reading
                if (winningId == null) winningId = c.getId();
                assertThat(c.getId()).isEqualTo(winningId);
            }
            assertThat(connectionRepository.findByProjectIdAndConnectorId(projectId, "posthog")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- #4 github per-project installation uniqueness ----------------------

    @Test
    void sameInstallationCannotBeConnectedTwiceInOneProject() {
        connectWithInstallation(projectId, "12345");
        assertThatThrownBy(() -> connectWithInstallation(projectId, "12345"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameInstallationCanBeConnectedAcrossDifferentProjects() {
        connectWithInstallation(projectId, "12345");
        connectWithInstallation(otherProjectId, "12345");

        List<Connection> routed = connectionRepository.findByConnectorIdAndConfigValue(
                "github", "installationId", "12345");
        assertThat(routed).hasSize(2);
        assertThat(routed).extracting(Connection::getProjectId)
                .containsExactlyInAnyOrder(projectId, otherProjectId);
    }

    private void connectWithInstallation(String project, String installationId) {
        Connection c = connectionService.create(project, "github", AuthType.APP, "repo", null);
        connectionService.updateConfig(c, java.util.Map.of("installationId", installationId));
    }
}
