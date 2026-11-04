package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AssetService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V178 backfills Creatives that were attached to a Post before attach started promoting them (#692). The
 * migration's SQL is loaded from the classpath file and run against the shared, already-migrated database,
 * so the test can't drift from what Flyway runs. Cases: attached DRAFT is promoted, unattached DRAFT is
 * not, attached ARCHIVED is not; {@code version} is unchanged on all three.
 */
class V178PromoteAttachedCreativesIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private WorkItem post;
    private BrandKit kit;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setFirebaseUid("uid-" + UUID.randomUUID());
        admin.setEmail(UUID.randomUUID() + "@example.com");
        admin.setName("Backfill Admin");
        admin = userRepository.save(admin);

        project = new Project();
        project.setName("V178 Test");
        project.setKey("VB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        workflowSeeder.seedMarketing(project);
        post = workItemService.createWorkItem(project.getId(), "POST", "Backfill post", "Caption", "MARKETING", admin);
        kit = brandKitService.resolveDefault(project.getId());
    }

    @Test
    void promotesOnlyDraftCreativesWithAFrameOnAPostAndLeavesVersionAlone() throws Exception {
        Creative attachedDraft = newCreative(1, Creative.STATE_DRAFT);
        Creative unattachedDraft = newCreative(2, Creative.STATE_DRAFT);
        Creative attachedArchived = newCreative(3, Creative.STATE_ARCHIVED);

        attachFrame(attachedDraft);
        newFrame(unattachedDraft);
        attachFrame(attachedArchived);

        Map<String, Object> before1 = row(attachedDraft);
        Map<String, Object> before2 = row(unattachedDraft);
        Map<String, Object> before3 = row(attachedArchived);

        String sql = new ClassPathResource("db/migration/V178__promote_attached_creatives.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);

        Map<String, Object> after1 = row(attachedDraft);
        assertThat(after1.get("state")).isEqualTo(Creative.STATE_READY);
        assertThat(after1.get("version")).isEqualTo(before1.get("version"));

        Map<String, Object> after2 = row(unattachedDraft);
        assertThat(after2.get("state")).isEqualTo(Creative.STATE_DRAFT);
        assertThat(after2.get("version")).isEqualTo(before2.get("version"));

        Map<String, Object> after3 = row(attachedArchived);
        assertThat(after3.get("state")).isEqualTo(Creative.STATE_ARCHIVED);
        assertThat(after3.get("version")).isEqualTo(before3.get("version"));
    }

    private Map<String, Object> row(Creative c) {
        return jdbc.queryForMap("SELECT state, version FROM creative WHERE id = ?", c.getId());
    }

    private Creative newCreative(int number, String state) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(kit.getId());
        c.setVariantLetter("a");
        c.setState(state);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline("Headline " + number);
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(number);
        return creativeRepository.save(c);
    }

    private CreativeRenderFrame newFrame(Creative creative) {
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);

        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey("9x16");
        frame.setPlatform("tiktok");
        frame.setGcsPath("projects/" + project.getId() + "/creatives/" + creative.getId() + "/9x16.jpg");
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1920);
        frame.setSizeBytes(4L);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    private void attachFrame(Creative creative) {
        CreativeRenderFrame frame = newFrame(creative);
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType("IMAGE");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(frame.getGcsPath());
        asset.setGcsPath(frame.getGcsPath());
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType("image/jpeg");
        asset.setCreativeFrameId(frame.getId());
        assetRepository.saveAndFlush(asset);
    }
}
