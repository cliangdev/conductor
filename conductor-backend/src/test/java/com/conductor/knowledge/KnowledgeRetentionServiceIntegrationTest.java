package com.conductor.knowledge;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageRevision;
import com.conductor.knowledge.page.KnowledgePageRevisionRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed: exercises {@link KnowledgeRetentionService} against real Postgres rows (and the real
 * Spring proxy for its {@code REQUIRES_NEW} per-row helpers -- the mock-based
 * {@link KnowledgeRetentionServiceTest} manually points {@code self} at the instance itself, which
 * would hide a regression where the self-reference wiring breaks). Uses the default retention windows
 * (30/90 days) rather than per-class overrides, to keep this test on the shared context.
 */
class KnowledgeRetentionServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgeRetentionService retentionService;
    @Autowired
    private KnowledgeSourceRepository sourceRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private KnowledgePageService pageService;
    @Autowired
    private KnowledgePageRepository pageRepository;
    @Autowired
    private KnowledgePageRevisionRepository revisionRepository;
    @Autowired
    private KnowledgeIngestionService ingestionService;

    private String projectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Retention Test Project");
        project.setKey("KR" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    private KnowledgeSource save(KnowledgeSourceStatus status, OffsetDateTime receivedAt,
                                  String payload, String payloadUri) {
        KnowledgeSource source = new KnowledgeSource();
        source.setProjectId(projectId);
        source.setSourceType("manual_note");
        source.setDedupKey(UUID.randomUUID().toString());
        source.setStatus(status);
        source.setReceivedAt(receivedAt);
        source.setPayload(payload);
        source.setPayloadUri(payloadUri);
        return sourceRepository.save(source);
    }

    @Test
    void compactsProcessedSourcePastThirtyDays_nullsPayloadAndStampsPurgedAt() {
        KnowledgeSource old = save(KnowledgeSourceStatus.PROCESSED,
                OffsetDateTime.now().minusDays(31), "old inline payload", null);

        retentionService.sweep();

        KnowledgeSource reloaded = sourceRepository.findById(old.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).isNull();
        assertThat(reloaded.getPurgedAt()).isNotNull();
    }

    @Test
    void leavesRecentProcessedSourceUntouched() {
        KnowledgeSource recent = save(KnowledgeSourceStatus.PROCESSED,
                OffsetDateTime.now().minusDays(5), "recent payload", null);

        retentionService.sweep();

        KnowledgeSource reloaded = sourceRepository.findById(recent.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).isEqualTo("recent payload");
        assertThat(reloaded.getPurgedAt()).isNull();
    }

    @Test
    void deletesOffloadedPayloadFromLocalStorageOnCompaction() {
        String gcsPath = "knowledge-sources/" + projectId + "/retention-test-" + UUID.randomUUID();
        KnowledgeSource offloaded = save(KnowledgeSourceStatus.PROCESSED,
                OffsetDateTime.now().minusDays(31), null, gcsPath);

        retentionService.sweep();

        KnowledgeSource reloaded = sourceRepository.findById(offloaded.getId()).orElseThrow();
        assertThat(reloaded.getPayloadUri()).isNull();
        assertThat(reloaded.getPurgedAt()).isNotNull();
    }

    /**
     * The read path must not assume an offloaded source's payload is always resolvable -- once
     * retention has nulled {@code payloadUri}, {@code KnowledgeIngestionService#getSources}' offload-
     * resolution branch (payload == null && payloadUri != null -> download) must no longer trigger, or
     * it would try to download an object that's been deleted from the bucket. Verified directly rather
     * than assumed from reading the code.
     */
    @Test
    void getSourcesReturnsNullPayloadGracefullyForCompactedOffloadedSource() {
        String gcsPath = "knowledge-sources/" + projectId + "/read-path-test-" + UUID.randomUUID();
        KnowledgeSource offloaded = save(KnowledgeSourceStatus.PROCESSED,
                OffsetDateTime.now().minusDays(31), null, gcsPath);

        retentionService.sweep();

        List<KnowledgeSourceView> views = ingestionService.getSources(projectId, List.of(offloaded.getId()));
        assertThat(views).hasSize(1);
        KnowledgeSourceView view = views.get(0);
        assertThat(view.payload()).isNull();
        assertThat(view.payloadOffloaded()).isFalse();
        assertThat(view.purgedAt()).isNotNull();
    }

    @Test
    void deletesDeadSourcePastNinetyDays() {
        KnowledgeSource dead = save(KnowledgeSourceStatus.DEAD,
                OffsetDateTime.now().minusDays(91), null, null);

        retentionService.sweep();

        assertThat(sourceRepository.findById(dead.getId())).isEmpty();
    }

    /** SKIPPED gets the same terminal-unfiled hard-delete treatment as DEAD (default 90-day window). */
    @Test
    void deletesSkippedSourcePastNinetyDays() {
        KnowledgeSource skipped = save(KnowledgeSourceStatus.SKIPPED,
                OffsetDateTime.now().minusDays(91), null, null);
        skipped.setSkipReason("not material");
        sourceRepository.save(skipped);

        retentionService.sweep();

        assertThat(sourceRepository.findById(skipped.getId())).isEmpty();
    }

    @Test
    void leavesRecentDeadSourceUntouched() {
        KnowledgeSource recentDead = save(KnowledgeSourceStatus.DEAD,
                OffsetDateTime.now().minusDays(10), null, null);

        retentionService.sweep();

        Optional<KnowledgeSource> reloaded = sourceRepository.findById(recentDead.getId());
        assertThat(reloaded).isPresent();
    }

    @Test
    void pendingAndProcessingRowsAreNeverTouchedRegardlessOfAge() {
        KnowledgeSource pending = save(KnowledgeSourceStatus.PENDING,
                OffsetDateTime.now().minusDays(200), "still pending", null);
        KnowledgeSource processing = save(KnowledgeSourceStatus.PROCESSING,
                OffsetDateTime.now().minusDays(200), "still processing", null);

        retentionService.sweep();

        KnowledgeSource reloadedPending = sourceRepository.findById(pending.getId()).orElseThrow();
        KnowledgeSource reloadedProcessing = sourceRepository.findById(processing.getId()).orElseThrow();
        assertThat(reloadedPending.getPayload()).isEqualTo("still pending");
        assertThat(reloadedPending.getPurgedAt()).isNull();
        assertThat(reloadedProcessing.getPayload()).isEqualTo("still processing");
        assertThat(reloadedProcessing.getPurgedAt()).isNull();
    }

    /**
     * The whole reason compaction nulls the payload instead of deleting the row (per the design
     * decision: {@code knowledge_revision_sources.source_id} has {@code ON DELETE CASCADE}, so hard-
     * deleting a PROCESSED source would silently erase the page revision's provenance link). Writes a
     * real page through {@link KnowledgePageService#batchWrite} referencing the source (which flips it
     * to PROCESSED in the same transaction as the write, exactly like the librarian does), then confirms
     * the revision-source link is still resolvable after the sweep compacts the source's payload away.
     */
    @Test
    void compactionPreservesRevisionSourceLinkForProvenance() {
        KnowledgeSource source = save(KnowledgeSourceStatus.PENDING,
                OffsetDateTime.now().minusDays(31), "raw note content", null);
        source.setSourceRef("manual:test-note");
        sourceRepository.save(source);

        PageWrite write = new PageWrite("provenance-test.md",
                "---\ntype: decision\ntitle: Test\n---\nBody content.", null, false);
        Actor actor = new Actor("user", "tester", null);
        pageService.batchWrite(projectId, List.of(write), List.of(source.getId()), actor);

        // markProcessed (inside batchWrite) flips the source to PROCESSED in the same transaction as
        // the page write that references it -- confirm that happened before the sweep runs.
        assertThat(sourceRepository.findById(source.getId()).orElseThrow().getStatus())
                .isEqualTo(KnowledgeSourceStatus.PROCESSED);

        KnowledgePage page = pageRepository.findByProjectIdAndPath(projectId, "provenance-test.md").orElseThrow();
        KnowledgePageRevision revision = revisionRepository.findByPage_IdOrderByVersionDesc(page.getId()).get(0);

        retentionService.sweep();

        List<KnowledgePageRevisionRepository.RevisionSourceRef> refs =
                revisionRepository.findSourceRefsByRevisionIds(List.of(revision.getId()));
        assertThat(refs).extracting(KnowledgePageRevisionRepository.RevisionSourceRef::getSourceRef)
                .containsExactly("manual:test-note");

        KnowledgeSource compacted = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(compacted.getPayload()).isNull();
        assertThat(compacted.getPurgedAt()).isNotNull();
    }

    /**
     * The DEAD hard-delete's provenance guard. A librarian run that outlives the stale window can link
     * a revision to a source <em>after</em> the scheduler's sweep dead-lettered it ({@code
     * markProcessed} only moves PENDING/PROCESSING rows, so the status stays DEAD) — hard-deleting such
     * a row would cascade away the {@code knowledge_revision_sources} link. The sweep must tombstone it
     * (compact + purgedAt) instead, and the tombstone must drop out of later sweeps' candidate batches.
     */
    @Test
    void referencedDeadSourceIsTombstonedNotDeleted() {
        KnowledgeSource source = save(KnowledgeSourceStatus.PENDING,
                OffsetDateTime.now().minusDays(91), "wedged-run content", null);
        source.setSourceRef("manual:wedged-note");
        sourceRepository.save(source);

        PageWrite write = new PageWrite("wedged-provenance.md",
                "---\ntype: decision\ntitle: Wedged\n---\nBody.", null, false);
        pageService.batchWrite(projectId, List.of(write), List.of(source.getId()), new Actor("user", "tester", null));

        // Recreate the race's end state: revision link exists, but the source ended up DEAD (the
        // stale sweep dead-lettered it before the wedged run's write landed).
        KnowledgeSource linked = sourceRepository.findById(source.getId()).orElseThrow();
        linked.setStatus(KnowledgeSourceStatus.DEAD);
        sourceRepository.save(linked);

        KnowledgePage page = pageRepository.findByProjectIdAndPath(projectId, "wedged-provenance.md").orElseThrow();
        KnowledgePageRevision revision = revisionRepository.findByPage_IdOrderByVersionDesc(page.getId()).get(0);

        retentionService.sweep();

        KnowledgeSource tombstone = sourceRepository.findById(source.getId()).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(KnowledgeSourceStatus.DEAD);
        assertThat(tombstone.getPayload()).isNull();
        assertThat(tombstone.getPurgedAt()).isNotNull();
        assertThat(revisionRepository.findSourceRefsByRevisionIds(List.of(revision.getId())))
                .extracting(KnowledgePageRevisionRepository.RevisionSourceRef::getSourceRef)
                .containsExactly("manual:wedged-note");

        // The tombstone is out of the candidate query now — a second sweep must not delete it either.
        retentionService.sweep();
        assertThat(sourceRepository.findById(source.getId())).isPresent();
    }

    @Test
    void sweepIsIdempotentOnAlreadyCompactedSource() {
        KnowledgeSource old = save(KnowledgeSourceStatus.PROCESSED,
                OffsetDateTime.now().minusDays(45), "payload", null);

        retentionService.sweep();
        OffsetDateTime firstPurgedAt = sourceRepository.findById(old.getId()).orElseThrow().getPurgedAt();
        retentionService.sweep();
        OffsetDateTime secondPurgedAt = sourceRepository.findById(old.getId()).orElseThrow().getPurgedAt();

        assertThat(secondPurgedAt).isEqualTo(firstPurgedAt);
    }
}
