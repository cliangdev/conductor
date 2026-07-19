package com.conductor.agent.credential;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres round-trip for the verification columns added in V96 — specifically the
 * {@code last_verification_report} JSONB column's {@code ?::jsonb} write transformer, which no
 * Mockito-level test can exercise.
 */
@Transactional
class ProviderCredentialRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private ProviderCredentialRepository repository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void verificationReportJsonbRoundTrips() {
        User user = new User();
        user.setFirebaseUid("test-firebase-uid-" + UUID.randomUUID());
        user.setEmail("cred-roundtrip@example.com");
        userRepository.save(user);
        Project project = new Project();
        project.setName("Cred Roundtrip");
        project.setKey(UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        String projectId = projectRepository.save(project).getId();

        String report = "{\"provider\":\"claude\",\"status\":\"verified\",\"checks\":"
                + "[{\"name\":\"anthropic-auth\",\"status\":\"pass\",\"message\":\"ok\"}]}";
        OffsetDateTime checkedAt = OffsetDateTime.now();

        ProviderCredential credential = new ProviderCredential();
        credential.setProjectId(projectId);
        credential.setProvider("claude");
        credential.setKmsKeyReference("test-kms-ref");
        credential.setEncryptedApiKey("test-ciphertext");
        credential.setLastVerifiedAt(checkedAt);
        credential.setLastVerificationStatus("verified");
        credential.setLastVerificationReport(report);
        repository.saveAndFlush(credential);
        entityManager.clear();

        ProviderCredential reloaded = repository.findByProjectIdAndProvider(projectId, "claude").orElseThrow();
        assertThat(reloaded.getLastVerificationStatus()).isEqualTo("verified");
        assertThat(reloaded.getLastVerifiedAt()).isNotNull();
        assertThat(reloaded.getLastVerificationReport()).contains("\"anthropic-auth\"");
    }
}
