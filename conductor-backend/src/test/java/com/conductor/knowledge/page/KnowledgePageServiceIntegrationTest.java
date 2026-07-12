package com.conductor.knowledge.page;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.KnowledgeSourceStatus;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed tests for {@link KnowledgePageService}: link-graph rebuild (incl. dangling-link
 * re-resolution and delete un-resolving incoming links) and revision provenance. Full application
 * context on the shared singleton Postgres -- see {@code docs/testing-guidelines.md}.
 */
@Transactional
class KnowledgePageServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgePageService pageService;
    @Autowired
    private KnowledgePageRepository pageRepository;
    @Autowired
    private KnowledgeLinkRepository linkRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private KnowledgeSourceRepository sourceRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Test Project");
        project.setKey("KT" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    private String doc(String type, String title, String body) {
        return "---\ntype: " + type + "\ntitle: " + title + "\n---\n\n" + body;
    }

    @Test
    void createThenUpdateRoundTripsThroughGetPages() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "First body."), null, false)),
                List.of(), new Actor("user", "u1", null));

        List<PageView> firstRead = pageService.getPages(projectId, List.of("notes/a.md"));
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).version()).isEqualTo(1);
        assertThat(firstRead.get(0).content()).contains("First body.");

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Second body."), 1, false)),
                List.of(), null);

        List<PageView> secondRead = pageService.getPages(projectId, List.of("notes/a.md"));
        assertThat(secondRead.get(0).version()).isEqualTo(2);
        assertThat(secondRead.get(0).content()).contains("Second body.");
    }

    @Test
    void linkToExistingPageResolvesImmediately() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/target.md", doc("note", "Target", "Body."), null, false)),
                List.of(), null);

        String fromDoc = doc("note", "From", "See [target](/notes/target.md) for more.");
        pageService.batchWrite(projectId, List.of(new PageWrite("notes/from.md", fromDoc, null, false)), List.of(), null);
        pageRepository.flush();

        KnowledgePage fromPage = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/from.md").orElseThrow();
        KnowledgePage targetPage = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/target.md").orElseThrow();
        List<KnowledgeLink> links = linkRepository.findAll().stream()
                .filter(l -> l.getFromPageId().equals(fromPage.getId())).toList();

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getToPath()).isEqualTo("notes/target.md");
        assertThat(links.get(0).getResolvedPageId()).isEqualTo(targetPage.getId());
    }

    @Test
    void danglingLinkResolvesOnceTargetIsCreated() {
        String fromDoc = doc("note", "From", "See [target](/notes/later.md) soon.");
        pageService.batchWrite(projectId, List.of(new PageWrite("notes/from.md", fromDoc, null, false)), List.of(), null);
        pageRepository.flush();

        KnowledgePage fromPage = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/from.md").orElseThrow();
        KnowledgeLink danglingLink = linkRepository.findAll().stream()
                .filter(l -> l.getFromPageId().equals(fromPage.getId())).findFirst().orElseThrow();
        assertThat(danglingLink.getResolvedPageId()).isNull();

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/later.md", doc("note", "Later", "Now exists."), null, false)),
                List.of(), null);
        pageRepository.flush();

        KnowledgePage targetPage = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/later.md").orElseThrow();
        KnowledgeLink resolved = linkRepository.findById(danglingLink.getId()).orElseThrow();
        assertThat(resolved.getResolvedPageId()).isEqualTo(targetPage.getId());
    }

    @Test
    void deletingTargetUnresolvesIncomingLinks() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/target.md", doc("note", "Target", "Body."), null, false)),
                List.of(), null);
        String fromDoc = doc("note", "From", "See [target](/notes/target.md).");
        pageService.batchWrite(projectId, List.of(new PageWrite("notes/from.md", fromDoc, null, false)), List.of(), null);
        pageRepository.flush();

        KnowledgePage targetPage = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/target.md").orElseThrow();
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/target.md", null, 1, true)), List.of(), null);
        pageRepository.flush();

        List<KnowledgeLink> links = linkRepository.findAll().stream()
                .filter(l -> l.getToPath().equals("notes/target.md")).toList();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getResolvedPageId()).isNull();
        assertThat(pageRepository.findById(targetPage.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void revisionsCarrySourceProvenanceAndActor() {
        KnowledgeSource source1 = newSource("slack-message", "slack://C123/p456");
        KnowledgeSource source2 = newSource("github-pr", "github://org/repo/pull/7");

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(source1.getId(), source2.getId()), new Actor("workflow", "wf-1", "run-42"));

        List<RevisionView> revisions = pageService.getRevisions(projectId, "notes/a.md");

        assertThat(revisions).hasSize(1);
        RevisionView revision = revisions.get(0);
        assertThat(revision.version()).isEqualTo(1);
        assertThat(revision.changeKind()).isEqualTo(KnowledgePageRevision.ChangeKind.CREATE);
        assertThat(revision.actor().kind()).isEqualTo("workflow");
        assertThat(revision.actor().workflowRunId()).isEqualTo("run-42");
        assertThat(revision.sourceRefs()).containsExactlyInAnyOrder("slack://C123/p456", "github://org/repo/pull/7");

        assertThat(sourceRepository.findById(source1.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.PROCESSED);
    }

    private KnowledgeSource newSource(String sourceType, String sourceRef) {
        KnowledgeSource source = new KnowledgeSource();
        source.setProjectId(projectId);
        source.setSourceType(sourceType);
        source.setSourceRef(sourceRef);
        source.setDedupKey(UUID.randomUUID().toString());
        return sourceRepository.saveAndFlush(source);
    }

    @Test
    void indexAndLogVirtualPagesAreGenerated() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body A."), null, false)),
                List.of(), null);

        List<PageView> views = pageService.getPages(projectId, List.of("index.md", "log.md"));
        assertThat(views).hasSize(2);

        PageView index = views.stream().filter(v -> v.path().equals("index.md")).findFirst().orElseThrow();
        assertThat(index.content()).contains("notes/a.md").contains("A");

        PageView log = views.stream().filter(v -> v.path().equals("log.md")).findFirst().orElseThrow();
        assertThat(log.content()).contains("**Update**: notes/a.md");
    }
}
