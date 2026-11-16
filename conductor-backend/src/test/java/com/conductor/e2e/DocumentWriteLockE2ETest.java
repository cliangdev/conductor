package com.conductor.e2e;

import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.service.DocumentContent;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code baseContentHash} check is read-compare-write, so it only works if writers of one document run one
 * after the other. Both the Work Item document and the project doc take a row lock before comparing; this proves
 * the lock is real (a second locker waits for the first's commit) and that two writers holding the same base hash
 * cannot both win.
 */
class DocumentWriteLockE2ETest extends AbstractE2ETest {

    @Autowired DocumentRepository documentRepository;
    @Autowired ProjectDocRepository projectDocRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.conductor.service.DocumentService documentService;
    @Autowired com.conductor.service.ProjectDocService projectDocService;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    HttpHeaders admin;
    String projectId;
    String workItemId;
    String documentId;
    String projectDocId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin-" + tag + "@doc-lock.test");
        projectId = (String) post("/api/v1/projects", Map.of("name", "Lock " + tag, "description", "t")).get("id");
        workItemId = (String) post("/api/v2/projects/" + projectId + "/work-items",
                Map.of("title", "PRD", "type", "PRD", "workflow", "ENGINEERING")).get("id");
        documentId = (String) rest.exchange(url(wiDocUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "v1", "contentType", "text/markdown"), admin), Map.class)
                .getBody().get("id");
        projectDocId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes")).get("id");
        rest.exchange(url(pdUrl()), HttpMethod.PUT, new HttpEntity<>(Map.of("content", "v1"), admin), Map.class);
    }

    @Test
    void theLockingQueriesMakeASecondWriterWaitForTheFirstToFinish() throws Exception {
        assertSecondLockerWaits(() -> documentRepository.findForUpdateByWorkItemIdAndFilename(workItemId, "spec.md"));
        assertSecondLockerWaits(() -> projectDocRepository.lockById(projectDocId));
    }

    @Test
    void twoWorkItemWritersWithTheSameBaseHashCannotBothWin() throws Exception {
        String base = DocumentContent.sha256Hex("v1");
        List<HttpStatus> results = raceTwo(() -> rest.exchange(url(wiDocUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "v2-" + UUID.randomUUID(), "contentType", "text/markdown",
                        "baseContentHash", base), admin), Map.class).getStatusCode());
        assertThat(results).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    @Test
    void twoProjectDocWritersWithTheSameBaseHashCannotBothWin() throws Exception {
        String base = DocumentContent.sha256Hex("v1");
        List<HttpStatus> results = raceTwo(() -> rest.exchange(url(pdUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "v2-" + UUID.randomUUID(), "baseContentHash", base), admin),
                Map.class).getStatusCode());
        assertThat(results).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    /**
     * The short overloads call the annotated ones through {@code this}, so without {@code @Transactional} of their
     * own they ran with no transaction at all; the row lock (which needs one) makes that visible as a failure.
     */
    @Test
    void theShortOverloadsOfTheWritePathsRunInATransaction() {
        org.springframework.security.core.context.SecurityContext machine =
                new org.springframework.security.core.context.SecurityContextImpl();
        machine.setAuthentication(new com.conductor.security.WorkflowRunAuthenticationToken(projectId, "run-1"));
        org.springframework.security.core.context.SecurityContextHolder.setContext(machine);
        try {
            assertThat(documentService.upsertDocumentByFilename(projectId, workItemId, "spec.md", "v2", "text/markdown"))
                    .as("existing document: updated, not created").isFalse();
            assertThat(jdbc.queryForObject("SELECT content FROM documents WHERE id = ?", String.class, documentId))
                    .isEqualTo("v2");

            projectDocService.updateDoc(projectId, projectDocId, "v2", com.conductor.service.ProjectActor.agent("test"));
            assertThat(jdbc.queryForObject("SELECT content FROM project_docs WHERE id = ?", String.class, projectDocId))
                    .isEqualTo("v2");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /**
     * The locking query hands back the instance the transaction already holds without reloading it. A caller that
     * read the document earlier in the same transaction (accepting a suggested edit does) must still be compared
     * against the text as it is once the lock is held, not the text it read before someone else's commit.
     */
    @Test
    void aWriterThatReadTheDocumentEarlierInItsTransactionStillSeesTheNewerCommit() throws Exception {
        String base = DocumentContent.sha256Hex("v1");
        org.springframework.security.core.context.SecurityContext machine =
                new org.springframework.security.core.context.SecurityContextImpl();
        machine.setAuthentication(new com.conductor.security.WorkflowRunAuthenticationToken(projectId, "run-1"));
        ExecutorService other = Executors.newSingleThreadExecutor();
        org.springframework.security.core.context.SecurityContextHolder.setContext(machine);
        try {
            Throwable wi = catchInTransaction(() -> {
                documentRepository.findById(documentId).orElseThrow(); // now held by this transaction
                commitElsewhere(other, "UPDATE documents SET content = 'theirs', content_hash = ? WHERE id = ?",
                        DocumentContent.sha256Hex("theirs"), documentId);
                documentService.upsertDocumentByFilename(projectId, workItemId, "spec.md", "mine", "text/markdown", base);
            });
            assertThat(wi).isInstanceOf(com.conductor.exception.ConflictException.class);
            assertThat(jdbc.queryForObject("SELECT content FROM documents WHERE id = ?", String.class, documentId))
                    .isEqualTo("theirs");

            Throwable pd = catchInTransaction(() -> {
                projectDocRepository.findById(projectDocId).orElseThrow();
                commitElsewhere(other, "UPDATE project_docs SET content = 'theirs' WHERE id = ?", projectDocId);
                projectDocService.updateDoc(projectId, projectDocId, "mine",
                        com.conductor.service.ProjectActor.agent("test"), base);
            });
            assertThat(pd).isInstanceOf(com.conductor.exception.ConflictException.class);
            assertThat(jdbc.queryForObject("SELECT content FROM project_docs WHERE id = ?", String.class, projectDocId))
                    .isEqualTo("theirs");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            other.shutdownNow();
        }
    }

    private Throwable catchInTransaction(Runnable body) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> body.run());
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void commitElsewhere(ExecutorService other, String sql, Object... args) {
        try {
            other.submit(() -> jdbc.update(sql, args)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------

    private void assertSecondLockerWaits(Runnable lockQuery) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                lockQuery.run();
                firstHoldsLock.countDown();
                await(releaseFirst);
            }));
            assertThat(firstHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> second = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(s -> lockQuery.run()));
            try {
                second.get(1, TimeUnit.SECONDS);
                throw new AssertionError("the second locker did not wait for the first transaction");
            } catch (java.util.concurrent.TimeoutException expected) {
                // still blocked on the row lock, as it should be
            }
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private List<HttpStatus> raceTwo(java.util.function.Supplier<org.springframework.http.HttpStatusCode> write)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return HttpStatus.valueOf(write.get().value());
                }));
            }
            go.countDown();
            List<HttpStatus> out = new ArrayList<>();
            for (Future<HttpStatus> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String wiDocUrl() {
        return "/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/spec.md";
    }

    private String pdUrl() {
        return "/api/v1/projects/" + projectId + "/docs/" + projectDocId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, admin), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private HttpHeaders login(String email) {
        var login = rest.postForEntity(url("/api/v1/auth/local"), Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
