package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.BusinessException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.service.publish.PublishPreflightService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate-less lifecycle. {@code marketing-autopilot.workflow.json} has no {@code requiresReview} edge, so
 * before the validators keyed on entry into the scheduled status it would have published anything: a
 * Draft with no fire time, no destination and no media could be moved straight to Scheduled. Now the
 * {@code DRAFT -> SCHEDULED} edge is the gate, and the scheduled region freezes content exactly as the
 * review region does on MARKETING — with "unscheduled" as the way back, since there is no reviewer to send
 * it back.
 */
class MarketingAutopilotGateIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final String AUTOPILOT = "MARKETING_AUTOPILOT";

    @Autowired private WorkItemService workItemService;
    @Autowired private PublishPreflightService publishPreflightService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired private WorkflowDefinitionVersionRepository workflowDefinitionVersionRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private PostPublishTargetRepository postPublishTargetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private AssetService assetService;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private WorkItem post;

    @BeforeEach
    void setUp() throws Exception {
        admin = newUser("Autopilot Admin");
        project = new Project();
        project.setName("Autopilot Gate");
        project.setKey("AP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);
        addMember(admin, MemberRole.ADMIN);
        seedAutopilot();

        post = workItemService.createWorkItem(project.getId(), "POST", "Autopilot teaser", "Body", AUTOPILOT, admin);
    }

    @Test
    void schedulingAnIncompleteDraftIsRefusedAndPreflightSaysWhy() {
        PublishPreflightService.Preflight preflight = publishPreflightService.preflight(project.getId(), post.getId(), admin);
        assertThat(preflight.publishing()).isTrue();
        assertThat(preflight.ready()).isFalse();
        assertThat(preflight.review().gated()).isFalse();
        assertThat(preflight.nextTransition().to()).isEqualTo("SCHEDULED");
        assertThat(preflight.nextTransition().requiresReview()).isFalse();
        assertThat(preflight.blockers()).extracting(f -> f.code())
                .contains(PostScheduleValidator.NO_FIRE_TIME, PostScheduleValidator.NO_TARGETS, PostScheduleValidator.NO_MEDIA);

        assertThatThrownBy(() -> moveTo("SCHEDULED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Cannot move Post to SCHEDULED")
                .hasMessageContaining("no fire time is set");
        assertThat(reload().getCurrentStatus()).isEqualTo("DRAFT");
    }

    @Test
    void aCompleteDraftSchedulesAndItsContentFreezesUntilUnscheduled() {
        completePublishBundle();
        assertThat(publishPreflightService.preflight(project.getId(), post.getId(), admin).ready()).isTrue();

        assertThatCode(() -> moveTo("SCHEDULED")).doesNotThrowAnyException();
        assertThat(reload().getCurrentStatus()).isEqualTo("SCHEDULED");

        // No reviewer to send it back, so a scheduled Post refuses edits and names the way out.
        assertThatThrownBy(() -> workItemService.patchWorkItem(project.getId(), post.getId(), null,
                "Rewritten", null, null, null, null, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked")
                .hasMessageContaining("unscheduled");
        String assetId = assetRepository.findAllByWorkItemId(post.getId()).get(0).getId();
        assertThatThrownBy(() -> assetService.deleteAsset(project.getId(), post.getId(), assetId, admin))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> moveTo("DRAFT")).doesNotThrowAnyException();
        assertThatCode(() -> workItemService.patchWorkItem(project.getId(), post.getId(), null,
                "Rewritten", null, null, null, null, admin)).doesNotThrowAnyException();
        assertThat(reload().getDescription()).isEqualTo("Rewritten");
    }

    // --- helpers ---

    private void seedAutopilot() throws Exception {
        JsonNode definition;
        try (InputStream in = new ClassPathResource("schema/examples/marketing-autopilot.workflow.json").getInputStream()) {
            definition = objectMapper.readTree(in);
        }
        WorkflowDefinition header = new WorkflowDefinition();
        header.setProject(project);
        header.setName(AUTOPILOT);
        header.setDefinition(definition);
        header.setVersion(1);
        header.setState("PUBLISHED");
        header.setArea("MARKETING");
        header.setSchemaVersion(1);
        header.setSidebarEnabled(true);
        header.setEnabled(true);
        WorkflowDefinition saved = workflowDefinitionRepository.save(header);
        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setWorkflowDefinition(saved);
        snapshot.setVersion(1);
        snapshot.setDefinition(definition);
        snapshot.setSchemaVersion(1);
        workflowDefinitionVersionRepository.save(snapshot);
    }

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }

    private void addMember(User user, MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.save(member);
    }

    private void completePublishBundle() {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("oauth2");
        connection.setStatus("ACTIVE");
        connection = connectionRepository.save(connection);

        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(reload());
        target.setConnectorId("meta");
        target.setConnectionId(connection.getId());
        target.setPlatform("instagram");
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PENDING);
        target.setIdempotencyKey("key-" + UUID.randomUUID());
        postPublishTargetRepository.save(target);

        String assetId = UUID.randomUUID().toString();
        String gcsPath = "marketing-assets/" + project.getId() + "/" + post.getId() + "/" + assetId + "-teaser.jpg";
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(reload());
        asset.setType("instagram_post");
        asset.setLabel("teaser.jpg");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(gcsPath);
        asset.setGcsPath(gcsPath);
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(2048L);
        asset.setWidth(1080);
        asset.setHeight(1350);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        assetRepository.save(asset);

        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(2), "America/New_York", admin);
    }

    private void moveTo(String status) {
        workItemService.patchWorkItem(project.getId(), post.getId(), null, null, status, null, null, null, admin);
    }

    private WorkItem reload() {
        return workItemRepository.findById(post.getId()).orElseThrow();
    }
}
