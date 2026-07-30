package com.conductor.knowledge.page;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeCurationPaths;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.KnowledgeSourceStatus;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.knowledge.domain.KnowledgeDomainState;
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
    @Autowired
    private KnowledgeDomainRepository domainRepository;

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

    /**
     * Guards {@code markProcessed}'s {@code WHERE status IN (PENDING, PROCESSING)} invariant against a
     * future edit -- a SKIPPED source already carries the librarian's own verdict (not worth a page), so
     * batchWrite referencing it (e.g. a stale/duplicate reference) must never silently flip it back to
     * PROCESSED.
     */
    @Test
    void batchWriteDoesNotMoveASkippedSourceToProcessed() {
        KnowledgeSource skipped = newSource("slack-message", "slack://C1/skipped");
        skipped.setStatus(KnowledgeSourceStatus.SKIPPED);
        skipped.setSkipReason("not material");
        sourceRepository.saveAndFlush(skipped);

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/b.md", doc("note", "B", "Body."), null, false)),
                List.of(skipped.getId()), new Actor("user", "u1", null));

        assertThat(sourceRepository.findById(skipped.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.SKIPPED);
    }

    /**
     * The headline mixed-batch case: some sources filed into a page, some deliberately skipped, in one
     * transaction. Guards that {@code sourceIds}/{@code skipped} settle independently of each other and
     * of the page writes.
     */
    @Test
    void mixedBatchLeavesFiledSourcesProcessedAndSkippedSourcesSkippedWithReason() {
        KnowledgeSource processed1 = newSource("slack-message", "slack://C1/1");
        KnowledgeSource processed2 = newSource("slack-message", "slack://C1/2");
        KnowledgeSource processed3 = newSource("slack-message", "slack://C1/3");
        KnowledgeSource skipped1 = newSource("slack-message", "slack://C1/4");
        KnowledgeSource skipped2 = newSource("slack-message", "slack://C1/5");
        KnowledgeSource skipped3 = newSource("slack-message", "slack://C1/6");
        KnowledgeSource skipped4 = newSource("slack-message", "slack://C1/7");

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body A."), null, false),
                        new PageWrite("notes/b.md", doc("note", "B", "Body B."), null, false)),
                List.of(processed1.getId(), processed2.getId(), processed3.getId()),
                List.of(new SkippedSource(skipped1.getId(), "duplicate of notes/a.md"),
                        new SkippedSource(skipped2.getId(), "duplicate of notes/a.md"),
                        new SkippedSource(skipped3.getId(), "not material"),
                        new SkippedSource(skipped4.getId(), "off-topic")),
                new Actor("user", "u1", null));

        assertThat(sourceRepository.findById(processed1.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.PROCESSED);
        assertThat(sourceRepository.findById(processed2.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.PROCESSED);
        assertThat(sourceRepository.findById(processed3.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.PROCESSED);

        KnowledgeSource reloaded1 = sourceRepository.findById(skipped1.getId()).orElseThrow();
        assertThat(reloaded1.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded1.getSkipReason()).isEqualTo("duplicate of notes/a.md");

        KnowledgeSource reloaded2 = sourceRepository.findById(skipped2.getId()).orElseThrow();
        assertThat(reloaded2.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded2.getSkipReason()).isEqualTo("duplicate of notes/a.md");

        KnowledgeSource reloaded3 = sourceRepository.findById(skipped3.getId()).orElseThrow();
        assertThat(reloaded3.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded3.getSkipReason()).isEqualTo("not material");

        KnowledgeSource reloaded4 = sourceRepository.findById(skipped4.getId()).orElseThrow();
        assertThat(reloaded4.getStatus()).isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(reloaded4.getSkipReason()).isEqualTo("off-topic");
    }

    /**
     * Guards the restructured early return ({@code batchWrite}'s empty-{@code writes} path): a batch
     * that is entirely skips with no page changes must still settle its sources, not silently mark
     * nothing.
     */
    @Test
    void emptyWritesWithOnlySkippedStillMarksSourcesSkipped() {
        KnowledgeSource source1 = newSource("slack-message", "slack://C1/only-skip-1");
        KnowledgeSource source2 = newSource("slack-message", "slack://C1/only-skip-2");

        List<PageWriteResult> results = pageService.batchWrite(projectId, List.of(), List.of(),
                List.of(new SkippedSource(source1.getId(), "nothing new here"),
                        new SkippedSource(source2.getId(), "nothing new here")),
                new Actor("user", "u1", null));

        assertThat(results).isEmpty();
        assertThat(sourceRepository.findById(source1.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.SKIPPED);
        assertThat(sourceRepository.findById(source2.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.SKIPPED);
    }

    /**
     * Mirrors {@link #batchWriteDoesNotMoveASkippedSourceToProcessed} for the skip direction: a DEAD
     * source referenced in {@code skipped} (e.g. a stale/duplicate reference) must not be silently
     * revived, and must not fail the rest of the batch.
     */
    @Test
    void deadSourceInSkippedIsNotMovedAndDoesNotFailTheBatch() {
        KnowledgeSource dead = newSource("slack-message", "slack://C1/dead");
        dead.setStatus(KnowledgeSourceStatus.DEAD);
        sourceRepository.saveAndFlush(dead);
        KnowledgeSource live = newSource("slack-message", "slack://C1/live");

        List<PageWriteResult> results = pageService.batchWrite(projectId, List.of(), List.of(),
                List.of(new SkippedSource(dead.getId(), "too late"), new SkippedSource(live.getId(), "too late")),
                new Actor("user", "u1", null));

        assertThat(results).isEmpty();
        assertThat(sourceRepository.findById(dead.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.DEAD);
        assertThat(sourceRepository.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.SKIPPED);
    }

    /** {@code knowledge_revision_sources} means "this source fed this edit" -- a skipped source fed
     *  nothing, so revision provenance must link only {@code sourceIds}, never {@code skipped} ids. */
    @Test
    void revisionsLinkOnlySourceIdsNeverSkippedIds() {
        KnowledgeSource filed = newSource("slack-message", "slack://C1/filed");
        KnowledgeSource skipped = newSource("slack-message", "slack://C1/declined");

        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/c.md", doc("note", "C", "Body C."), null, false)),
                List.of(filed.getId()), List.of(new SkippedSource(skipped.getId(), "not material")),
                new Actor("user", "u1", null));

        List<RevisionView> revisions = pageService.getRevisions(projectId, "notes/c.md");
        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).sourceRefs()).containsExactly("slack://C1/filed");
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
        // First revision on a brand-new page is a CREATE, not an UPDATE -- buildVirtualLog now emits
        // the real changeKind label instead of hardcoding "Update" for every revision. The parenthesized
        // instant is the revision's real createdAt, not just the day heading -- see buildVirtualLog.
        assertThat(log.content()).containsPattern("\\*\\*Create\\*\\* \\([^)]+\\): notes/a\\.md");
    }

    // --- dismissPage ("Not worth filing") ---

    private KnowledgeDomain activeDomain(String slug, String displayName) {
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setProjectId(projectId);
        domain.setSlug(slug);
        domain.setDisplayName(displayName);
        domain.setPathPrefix(slug + "/");
        domain.setSchemaPagePath(slug + "/_schema.md");
        domain.setState(KnowledgeDomainState.ACTIVE);
        return domainRepository.saveAndFlush(domain);
    }

    @Test
    void dismissSoftDeletesThePageAndAppendsTheRuleToTheDomainsCurationPage() {
        activeDomain("engineering", "Engineering");
        pageService.batchWrite(projectId,
                List.of(new PageWrite("engineering/work-items/cx-14.md", doc("note", "CX-14", "Nothing happened."), null, false)),
                List.of(), new Actor("user", "u1", null));

        KnowledgePageService.DismissResult result = pageService.dismissPage(projectId,
                "engineering/work-items/cx-14.md", 1, "Ticket created and closed with nothing done.",
                "Casey Liang", new Actor("user", "u1", null));

        assertThat(result.path()).isEqualTo("engineering/work-items/cx-14.md");
        assertThat(result.curationPath()).isEqualTo("engineering/_curation.md");

        KnowledgePage page = pageRepository.findByProjectIdAndPath(projectId, "engineering/work-items/cx-14.md").orElseThrow();
        assertThat(page.isDeleted()).isTrue();
        assertThat(page.getVersion()).isEqualTo(2);

        KnowledgePage curation = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "engineering/_curation.md").orElseThrow();
        assertThat(curation.getVersion()).isEqualTo(result.curationVersion());
        assertThat(curation.getBody())
                .contains("## Do-not-file rules")
                .contains("`engineering/work-items/cx-14.md`")
                .contains("Ticket created and closed with nothing done.")
                .contains("dismissed by Casey Liang");
    }

    @Test
    void aRootLevelPageRoutesToTheRootCurationPage() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));

        KnowledgePageService.DismissResult result = pageService.dismissPage(projectId, "notes/a.md", 1,
                "Not worth keeping.", "Casey Liang", new Actor("user", "u1", null));

        assertThat(result.curationPath()).isEqualTo(KnowledgeCurationPaths.ROOT);
        KnowledgePage curation = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, KnowledgeCurationPaths.ROOT).orElseThrow();
        assertThat(curation.getBody()).contains("`notes/a.md`").contains("Not worth keeping.");
    }

    @Test
    void aMissingCurationPageIsCreatedFromTheTemplate() {
        // No prior batchWrite ever touched _curation.md -- this project never got the provisioner's seed.
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));
        assertThat(pageRepository.findByProjectIdAndPath(projectId, KnowledgeCurationPaths.ROOT)).isEmpty();

        KnowledgePageService.DismissResult result = pageService.dismissPage(projectId, "notes/a.md", 1,
                "Not worth keeping.", "Casey Liang", new Actor("user", "u1", null));

        assertThat(result.curationVersion()).isEqualTo(1);
        KnowledgePage curation = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, KnowledgeCurationPaths.ROOT).orElseThrow();
        assertThat(curation.getPageType()).isEqualTo("schema");
        assertThat(curation.getBody()).contains("## Do-not-file rules").contains("`notes/a.md`");
    }

    @Test
    void theAppendedBulletLandsInsideTheSectionAndFrontmatterStillParses() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));

        pageService.dismissPage(projectId, "notes/a.md", 1, "Not worth keeping.", "Casey Liang",
                new Actor("user", "u1", null));

        List<PageView> views = pageService.getPages(projectId, List.of(KnowledgeCurationPaths.ROOT));
        assertThat(views).hasSize(1);
        String content = views.get(0).content();

        // Round-trips through the same parser every read/write goes through -- proves the frontmatter
        // block wasn't corrupted by the body edit.
        FrontmatterParser.Parsed parsed = new FrontmatterParser().parse(content);
        assertThat(parsed.type()).isEqualTo("schema");

        int headingIdx = parsed.body().indexOf("## Do-not-file rules");
        int bulletIdx = parsed.body().indexOf("`notes/a.md`");
        assertThat(headingIdx).isGreaterThanOrEqualTo(0);
        assertThat(bulletIdx).isGreaterThan(headingIdx);
    }

    @Test
    void aStalePageBaseVersionThrowsAndLeavesCurationPageAtItsPriorVersion() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));
        // Seed the curation page first via one dismissal, so there's a "prior version" to protect.
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/b.md", doc("note", "B", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));
        pageService.dismissPage(projectId, "notes/b.md", 1, "Not worth keeping.", "Casey Liang",
                new Actor("user", "u1", null));
        KnowledgePage curationBefore = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, KnowledgeCurationPaths.ROOT).orElseThrow();
        int curationVersionBefore = curationBefore.getVersion();

        assertThatThrownBy(() -> pageService.dismissPage(projectId, "notes/a.md", 99, "Stale attempt.",
                "Casey Liang", new Actor("user", "u1", null)))
                .isInstanceOf(KnowledgeConflictException.class);

        KnowledgePage pageAfter = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, "notes/a.md").orElseThrow();
        assertThat(pageAfter.getVersion()).isEqualTo(1);
        KnowledgePage curationAfter = pageRepository.findByProjectIdAndPathAndDeletedFalse(projectId, KnowledgeCurationPaths.ROOT).orElseThrow();
        assertThat(curationAfter.getVersion()).isEqualTo(curationVersionBefore);
    }

    @Test
    void dismissingTheRootCurationPageThrowsBusinessException() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));
        pageService.dismissPage(projectId, "notes/a.md", 1, "Not worth keeping.", "Casey Liang",
                new Actor("user", "u1", null));

        assertThatThrownBy(() -> pageService.dismissPage(projectId, KnowledgeCurationPaths.ROOT, 1, "Nope.",
                "Casey Liang", new Actor("user", "u1", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dismissingASchemaPageThrowsBusinessException() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("_schema.md", doc("schema", "Schema", "Style guide."), null, false)),
                List.of(), new Actor("user", "u1", null));

        assertThatThrownBy(() -> pageService.dismissPage(projectId, "_schema.md", 1, "Nope.", "Casey Liang",
                new Actor("user", "u1", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anAlreadyDeletedPathThrowsEntityNotFoundRatherThanConflict() {
        pageService.batchWrite(projectId,
                List.of(new PageWrite("notes/a.md", doc("note", "A", "Body."), null, false)),
                List.of(), new Actor("user", "u1", null));
        pageService.batchWrite(projectId, List.of(new PageWrite("notes/a.md", null, 1, true)), List.of(), null);

        assertThatThrownBy(() -> pageService.dismissPage(projectId, "notes/a.md", 2, "Already gone.",
                "Casey Liang", new Actor("user", "u1", null)))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
