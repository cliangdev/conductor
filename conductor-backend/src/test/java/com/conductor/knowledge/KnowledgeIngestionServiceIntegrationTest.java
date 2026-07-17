package com.conductor.knowledge;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed test for {@link KnowledgeIngestionService}: the {@code (project_id, dedup_key)} unique
 * constraint drives claim-or-load idempotency -- a repeat submission (same derived or caller-supplied
 * dedup key) must return DUPLICATE against the original row, never insert a second one.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code KnowledgeIngestionService#submit} inserts via a
 * {@code REQUIRES_NEW} nested transaction (claim-or-load, mirroring {@code ActionInvocationService}),
 * which would suspend and be unable to see this test's setup data (the project row) if it were still
 * sitting uncommitted in an outer test transaction. Isolation instead comes from each test using its
 * own random project id, per {@code docs/testing-guidelines.md}'s shared-database contract.
 */
class KnowledgeIngestionServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgeIngestionService ingestionService;
    @Autowired
    private KnowledgeSourceRepository sourceRepository;
    @Autowired
    private KnowledgeDomainRepository domainRepository;
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
        project.setName("Ingestion Test Project");
        project.setKey("IN" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    @Test
    void repeatSubmissionWithDerivedDedupKeyReturnsDuplicate() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "slack-message", "slack://C1/p1",
                "A message", "text/plain", "hello world", occurredAt, null, null, null, null);

        SourceReceipt first = ingestionService.submit(submission);
        SourceReceipt second = ingestionService.submit(submission);

        assertThat(first.status()).isEqualTo(SourceReceipt.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(SourceReceipt.Status.DUPLICATE);
        assertThat(second.sourceId()).isEqualTo(first.sourceId());
        assertThat(sourceRepository.findByProjectIdAndIdIn(projectId, List.of(first.sourceId()))).hasSize(1);
    }

    @Test
    void repeatSubmissionWithExplicitDedupKeyReturnsDuplicate() {
        KnowledgeSubmission submissionA = new KnowledgeSubmission(projectId, "github-pr", "github://org/repo/pull/1",
                "PR opened", "text/plain", "payload A", null, "explicit-key", null, null, null);
        KnowledgeSubmission submissionB = new KnowledgeSubmission(projectId, "github-pr", "github://org/repo/pull/1",
                "PR updated", "text/plain", "payload B", null, "explicit-key", null, null, null);

        SourceReceipt first = ingestionService.submit(submissionA);
        SourceReceipt second = ingestionService.submit(submissionB);

        assertThat(first.status()).isEqualTo(SourceReceipt.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(SourceReceipt.Status.DUPLICATE);
        assertThat(second.sourceId()).isEqualTo(first.sourceId());
    }

    @Test
    void distinctSubmissionsGetDistinctRows() {
        KnowledgeSubmission a = new KnowledgeSubmission(projectId, "slack-message", "slack://C1/p1",
                null, "text/plain", "first", OffsetDateTime.now(), null, null, null, null);
        KnowledgeSubmission b = new KnowledgeSubmission(projectId, "slack-message", "slack://C1/p2",
                null, "text/plain", "second", OffsetDateTime.now(), null, null, null, null);

        SourceReceipt first = ingestionService.submit(a);
        SourceReceipt second = ingestionService.submit(b);

        assertThat(first.status()).isEqualTo(SourceReceipt.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(SourceReceipt.Status.ACCEPTED);
        assertThat(second.sourceId()).isNotEqualTo(first.sourceId());
    }

    @Test
    void listSourcesReturnsPendingSourcesForProject() {
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", "note://1",
                "A note", "text/plain", "some content", null, null, null, null, null);
        SourceReceipt receipt = ingestionService.submit(submission);

        List<KnowledgeSourceView> pending = ingestionService.listSources(projectId, KnowledgeSourceStatus.PENDING, null);

        assertThat(pending).extracting(KnowledgeSourceView::id).contains(receipt.sourceId());
        assertThat(pending.stream().filter(v -> v.id().equals(receipt.sourceId())).findFirst().orElseThrow().payload())
                .isEqualTo("some content");
    }

    // ---- domain-lane resolution (KnowledgeDomainResolver, wired through submit()) ----

    @Test
    void submitWithNoExplicitDomainAndNoMatchingPatternStampsNullLane() {
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", "note://2",
                null, "text/plain", "content", null, null, null, null, null);

        SourceReceipt receipt = ingestionService.submit(submission);

        KnowledgeSource stored = sourceRepository.findById(receipt.sourceId()).orElseThrow();
        assertThat(stored.getDomain()).isNull();
    }

    @Test
    void submitWithExplicitUnknownDomainThrows() {
        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", "note://3",
                null, "text/plain", "content", null, null, null, null, "nonexistent-domain");

        assertThatThrownBy(() -> ingestionService.submit(submission))
                .isInstanceOf(com.conductor.exception.BusinessException.class);
    }

    @Test
    void submitWithExplicitActiveDomainStampsIt() {
        domainRepository.save(activeDomain("engineering", List.of()));

        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "manual-note", "note://4",
                null, "text/plain", "content", null, null, null, null, "engineering");
        SourceReceipt receipt = ingestionService.submit(submission);

        KnowledgeSource stored = sourceRepository.findById(receipt.sourceId()).orElseThrow();
        assertThat(stored.getDomain()).isEqualTo("engineering");
    }

    @Test
    void submitMatchingRegistryGlobPatternStampsThatDomain() {
        domainRepository.save(activeDomain("engineering", List.of("github.*")));

        KnowledgeSubmission submission = new KnowledgeSubmission(projectId, "github.pr_merged", "github://org/repo/pull/1",
                null, "text/plain", "content", null, null, null, null, null);
        SourceReceipt receipt = ingestionService.submit(submission);

        KnowledgeSource stored = sourceRepository.findById(receipt.sourceId()).orElseThrow();
        assertThat(stored.getDomain()).isEqualTo("engineering");
    }

    private com.conductor.knowledge.domain.KnowledgeDomain activeDomain(String slug, List<String> patterns) {
        com.conductor.knowledge.domain.KnowledgeDomain domain = new com.conductor.knowledge.domain.KnowledgeDomain();
        domain.setProjectId(projectId);
        domain.setSlug(slug);
        domain.setDisplayName(slug);
        domain.setPathPrefix(slug + "/");
        domain.setSchemaPagePath(slug + "/_schema.md");
        domain.setSourceTypePatterns(patterns);
        domain.setState(com.conductor.knowledge.domain.KnowledgeDomainState.ACTIVE);
        return domain;
    }
}
