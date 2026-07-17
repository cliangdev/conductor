package com.conductor.knowledge.page;

import com.conductor.entity.Project;
import com.conductor.entity.User;
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

/** DB-backed test for {@link KnowledgeSearchService} -- full-text search over the generated tsvector. */
@Transactional
class KnowledgeSearchServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgePageService pageService;
    @Autowired
    private KnowledgePageRepository pageRepository;
    @Autowired
    private KnowledgeSearchService searchService;
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
        project.setName("Search Test Project");
        project.setKey("SR" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    @Test
    void searchReturnsRankedHitWithSnippet() {
        String deployDoc = "---\ntype: runbook\ntitle: Deploy Runbook\ndescription: How to deploy the service\n---\n\n"
                + "This runbook explains the deployment process for the backend service in detail.";
        String unrelatedDoc = "---\ntype: note\ntitle: Lunch Menu\n---\n\nTuesday is taco day.";

        pageService.batchWrite(projectId, List.of(
                new PageWrite("runbooks/deploy.md", deployDoc, null, false),
                new PageWrite("notes/lunch.md", unrelatedDoc, null, false)
        ), List.of(), null);
        pageRepository.flush();

        List<SearchHit> hits = searchService.search(projectId, "deploy", null, null, null);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.path()).isEqualTo("runbooks/deploy.md");
        assertThat(hit.type()).isEqualTo("runbook");
        assertThat(hit.title()).isEqualTo("Deploy Runbook");
        assertThat(hit.snippet()).containsIgnoringCase("deploy");
        assertThat(hit.rank()).isGreaterThan(0d);
    }

    @Test
    void searchExcludesDeletedPages() {
        String doc = "---\ntype: note\ntitle: Ephemeral\n---\n\nThis mentions gigawatt frobnication uniquely.";
        pageService.batchWrite(projectId, List.of(new PageWrite("notes/ephemeral.md", doc, null, false)), List.of(), null);
        pageRepository.flush();

        pageService.batchWrite(projectId, List.of(new PageWrite("notes/ephemeral.md", null, 1, true)), List.of(), null);
        pageRepository.flush();

        List<SearchHit> hits = searchService.search(projectId, "frobnication", null, null, null);
        assertThat(hits).isEmpty();
    }

    @Test
    void searchRespectsTypeFilter() {
        String runbookDoc = "---\ntype: runbook\ntitle: Widget Runbook\n---\n\nWidget handling procedure.";
        String noteDoc = "---\ntype: note\ntitle: Widget Note\n---\n\nWidget handling procedure.";
        pageService.batchWrite(projectId, List.of(
                new PageWrite("runbooks/widget.md", runbookDoc, null, false),
                new PageWrite("notes/widget.md", noteDoc, null, false)
        ), List.of(), null);
        pageRepository.flush();

        List<SearchHit> hits = searchService.search(projectId, "widget", "runbook", null, null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path()).isEqualTo("runbooks/widget.md");
    }
}
