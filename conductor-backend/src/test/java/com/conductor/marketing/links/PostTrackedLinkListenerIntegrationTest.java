package com.conductor.marketing.links;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every new Post in a publishing Workflow gets one campaign link, and a project that is not set up for App Store
 * links never has its Post creation touched. Posts are created through {@link WorkItemService#createWorkItem}, the
 * one path the web app, the MCP {@code create_post} tool and agents all take.
 */
@Transactional
class PostTrackedLinkListenerIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final ProjectActor ACTOR = ProjectActor.agent("links-test");

    @Autowired private WorkItemService workItemService;
    @Autowired private TrackedLinkService service;
    @Autowired private TrackedLinkRepository linkRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private User user;
    private Project project;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setFirebaseUid("post-link-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Post Links Test");
        user = userRepository.save(u);
        project = newProject();
    }

    @Test
    void aNewPostGetsOneCampaignLinkWithTheRightUrlTokenAndLabel() {
        connect(project, "{\"appId\":\"1234567890\",\"providerToken\":\"117962345\"}");

        WorkItem post = createPost(project, "Launch teaser");

        List<TrackedLink> links = linkRepository.findAll().stream()
                .filter(l -> post.getId().equals(l.getWorkItemId())).toList();
        assertThat(links).singleElement().satisfies(link -> {
            String token = "post-" + project.getKey().toLowerCase() + "-" + post.getSequenceNumber();
            assertThat(link.getKind()).isEqualTo("POST");
            assertThat(link.getFamily()).isEqualTo("posts");
            assertThat(link.getToken()).isEqualTo(token).hasSizeLessThanOrEqualTo(40);
            assertThat(link.getLabel()).isEqualTo("Launch teaser");
            assertThat(link.getProjectId()).isEqualTo(project.getId());
            assertThat(link.getUrl()).isEqualTo(
                    "https://apps.apple.com/app/apple-store/id1234567890?pt=117962345&ct=" + token);
        });
    }

    @Test
    void eachPostGetsItsOwnLinkAndTheLinksRollUpIntoThePostsFamily() {
        connect(project, "{\"appId\":\"1234567890\",\"providerToken\":\"117962345\"}");

        WorkItem first = createPost(project, "One");
        WorkItem second = createPost(project, "Two");

        assertThat(service.list(project.getId(), "POST", null, 10).links())
                .extracting(TrackedLink::getWorkItemId).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(service.performance(project.getId(), null, null).families())
                .singleElement().satisfies(family -> {
                    assertThat(family.family()).isEqualTo("posts");
                    assertThat(family.tokens()).isEqualTo(2);
                });
    }

    @Test
    void aLongPostTitleIsTrimmedToTheLabelLimit() {
        connect(project, "{\"appId\":\"1\",\"providerToken\":\"2\"}");

        WorkItem post = createPost(project, "t".repeat(250));

        assertThat(linkRepository.findAll().stream().filter(l -> post.getId().equals(l.getWorkItemId())))
                .singleElement().satisfies(link -> assertThat(link.getLabel()).hasSize(200));
    }

    @Test
    void aWorkItemOnAWorkflowThatDoesNotPublishGetsNoLink() {
        connect(project, "{\"appId\":\"1\",\"providerToken\":\"2\"}");
        workflowSeeder.seedEngineering(project);

        WorkItem prd = workItemService.createWorkItem(project.getId(), "PRD", "A spec", null, "ENGINEERING", ACTOR);

        assertThat(linkRepository.findAll()).noneMatch(l -> prd.getId().equals(l.getWorkItemId()));
    }

    @Test
    void withoutAProviderTokenThePostIsCreatedWithNoLinkAndNoError() {
        connect(project, "{\"appId\":\"1234567890\"}");

        WorkItem post = createPost(project, "No token yet");

        assertThat(workItemRepository.findById(post.getId())).isPresent();
        assertThat(linkRepository.findAll()).noneMatch(l -> post.getId().equals(l.getWorkItemId()));
    }

    @Test
    void withoutAnAppStoreConnectConnectionThePostIsCreatedWithNoLinkAndNoError() {
        WorkItem post = createPost(project, "No connection");

        assertThat(workItemRepository.findById(post.getId())).isPresent();
        assertThat(linkRepository.findAll()).noneMatch(l -> post.getId().equals(l.getWorkItemId()));
    }

    @Test
    void aConnectionConfigThatIsNotJsonIsTreatedAsNotSetUp() {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("app-store-connect");
        connection.setAuthType("API_KEY");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionRepository.saveAndFlush(connection);
        jdbc.update("UPDATE connection SET config_json = '\"just a string\"'::jsonb WHERE id = ?", connection.getId());
        entityManager.clear();

        WorkItem post = createPost(project, "Odd config");

        assertThat(workItemRepository.findById(post.getId())).isPresent();
        assertThat(linkRepository.findAll()).noneMatch(l -> post.getId().equals(l.getWorkItemId()));
    }

    @Test
    void aPostNeverGetsASecondLink() {
        connect(project, "{\"appId\":\"1\",\"providerToken\":\"2\"}");
        WorkItem post = createPost(project, "Once only");

        assertThat(service.createForPost(post)).isFalse();

        assertThat(linkRepository.findAll().stream().filter(l -> post.getId().equals(l.getWorkItemId()))).hasSize(1);
        TrackedLink second = new TrackedLink();
        second.setProjectId(project.getId());
        second.setKind("POST");
        second.setFamily("posts");
        second.setToken("another-token");
        second.setUrl("https://apps.apple.com/app/apple-store/id1?pt=2&ct=another-token");
        second.setWorkItemId(post.getId());
        assertThatThrownBy(() -> linkRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTokenAnExplicitLinkAlreadyHoldsDoesNotBlockPostCreation() {
        connect(project, "{\"appId\":\"1\",\"providerToken\":\"2\"}");
        service.create(project.getId(),
                new TrackedLinkService.LinkInput("OTHER", "misc", "post-" + project.getKey().toLowerCase() + "-1",
                        null, null), user.getId());

        WorkItem post = createPost(project, "Unlucky token");

        assertThat(post.getSequenceNumber()).isEqualTo(1);
        assertThat(workItemRepository.findById(post.getId())).isPresent();
        assertThat(linkRepository.findAll()).noneMatch(l -> post.getId().equals(l.getWorkItemId()));
    }

    @Test
    void deletingAPostKeepsItsLinkWithoutThePost() {
        connect(project, "{\"appId\":\"1\",\"providerToken\":\"2\"}");
        WorkItem post = createPost(project, "Short lived");
        String postId = post.getId();
        entityManager.flush();

        jdbc.update("DELETE FROM work_items WHERE id = ?", postId);

        List<String> remaining = jdbc.queryForList(
                "SELECT token FROM tracked_link WHERE project_id = ? AND work_item_id IS NULL", String.class,
                project.getId());
        assertThat(remaining).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tracked_link WHERE work_item_id = ?", Long.class, postId))
                .isZero();
    }

    private WorkItem createPost(Project in, String title) {
        return workItemService.createWorkItem(in.getId(), "POST", title, "Body", "MARKETING", ACTOR);
    }

    private Project newProject() {
        Project p = new Project();
        p.setName("Post Links Project");
        p.setKey("PL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        Project saved = projectRepository.save(p);
        workflowSeeder.seedMarketing(saved);
        return saved;
    }

    private void connect(Project in, String configJson) {
        Connection connection = new Connection();
        connection.setProjectId(in.getId());
        connection.setConnectorId("app-store-connect");
        connection.setDisplayLabel("App Store Connect");
        connection.setAuthType("API_KEY");
        connection.setStatus("ACTIVE");
        connection.setConfigJson(configJson);
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionRepository.saveAndFlush(connection);
    }
}
