package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.service.AssetLibraryService.LibraryAsset;
import com.conductor.service.AssetLibraryService.LibraryQuery;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Area asset library (COND-23 T7.1) against a real database.
 *
 * <p>The load-bearing assertion here is
 * {@link #aSecondWorkflowSeededIntoTheAreaContributesItsAssetsWithNoLibraryChange}: the Marketing Area holds
 * one Workflow (Posts) today and gains Content and SEO with COND-19, and their media has to appear in this
 * library without touching {@link AssetLibraryService}. So the test registers a second Workflow row in the
 * same Area and asserts its assets show up — if the query ever regresses to naming a Workflow slug or noun,
 * that test is what fails.
 *
 * <p>An integration test rather than a mocked unit test because everything being proved is query behavior —
 * the Area→Workflow join, the kind/upload-status predicates, the media-type and Workflow filters — which a
 * mocked repository would assert nothing about. Extends the shared-Postgres base (no workflow jobs are
 * enqueued here) and isolates itself inside a freshly created project per {@code docs/testing-guidelines.md}.
 */
class AssetLibraryServiceTest extends AbstractNoneWebIntegrationTest {

    private static final String MARKETING_AREA = "MARKETING";
    private static final String MARKETING_SLUG = "MARKETING";
    private static final String CONTENT_SLUG = "CONTENT";
    private static final String ENGINEERING_AREA = "ENGINEERING";
    private static final String ENGINEERING_SLUG = "ENGINEERING";

    @Autowired private AssetLibraryService assetLibraryService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private ObjectMapper objectMapper;

    private User member;
    private User outsider;
    private Project project;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        member = newUser("Marketing Lead");
        outsider = newUser("Somebody Else");

        project = new Project();
        project.setName("Asset Library");
        project.setKey("AL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(member);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(member);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        workflow(MARKETING_SLUG, MARKETING_AREA);
        workflow(ENGINEERING_SLUG, ENGINEERING_AREA);
    }

    // [auto] The endpoint lists uploaded file assets across all work items in an area with signed preview URLs

    @Test
    void everyUploadedFileAssetInTheAreaIsListedWithItsOwningWorkItem() {
        WorkItem teaser = post("Launch teaser", "DRAFT");
        WorkItem recap = post("Launch recap", "IN_REVIEW");
        WorkItem thread = post("Launch thread", "PUBLISHED");
        Asset a = uploadedFile(teaser, "image/png", 1_000L);
        Asset b = uploadedFile(recap, "image/jpeg", 2_000L);
        Asset c = uploadedFile(thread, "video/mp4", 3_000L);

        List<LibraryAsset> library = list(LibraryQuery.unfiltered());

        assertThat(library).extracting(LibraryAsset::assetId)
                .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(library).extracting(LibraryAsset::workItemDisplayId)
                .containsExactlyInAnyOrder(displayId(teaser), displayId(recap), displayId(thread));
        assertThat(library).allSatisfy(row -> {
            assertThat(row.previewUrl()).isNotBlank();
            assertThat(row.workflow()).isEqualTo(MARKETING_SLUG);
            assertThat(row.uploadedAt()).isNotNull();
        });

        LibraryAsset teaserRow = rowFor(library, a.getId());
        assertThat(teaserRow.contentType()).isEqualTo("image/png");
        assertThat(teaserRow.sizeBytes()).isEqualTo(1_000L);
        assertThat(teaserRow.workItemId()).isEqualTo(teaser.getId());
        assertThat(teaserRow.workItemTitle()).isEqualTo("Launch teaser");
        assertThat(teaserRow.workItemStatus()).isEqualTo("DRAFT");
        // The preview URL is a read URL for this object specifically, minted per response and never stored.
        assertThat(teaserRow.previewUrl()).contains(a.getId());
    }

    // [auto] The media-type filter narrows to images or videos

    @Test
    void theMediaTypeFilterNarrowsToImagesOrVideos() {
        WorkItem post = post("Mixed media", "DRAFT");
        Asset png = uploadedFile(post, "image/png", 10L);
        Asset mp4 = uploadedFile(post, "video/mp4", 20L);

        assertThat(list(filter("video"))).extracting(LibraryAsset::assetId).containsExactly(mp4.getId());
        assertThat(list(filter("image"))).extracting(LibraryAsset::assetId).containsExactly(png.getId());
    }

    // [auto] Non-members are denied access

    @Test
    void aNonMemberIsDeniedTheAreaLibrary() {
        uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);

        assertThatThrownBy(() -> assetLibraryService.listAreaAssets(
                project.getId(), MARKETING_AREA, LibraryQuery.unfiltered(), 0, 50, outsider))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // [auto] A second workflow in the same area contributes its assets with no library code change

    @Test
    void aSecondWorkflowSeededIntoTheAreaContributesItsAssetsWithNoLibraryChange() {
        Asset postAsset = uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);

        // COND-19's Content workflow, joining the Marketing Area after the library shipped.
        workflow(CONTENT_SLUG, MARKETING_AREA);
        WorkItem article = workItem("Founder story", "DRAFT", CONTENT_SLUG);
        Asset articleAsset = uploadedFile(article, "image/png", 20L);

        assertThat(list(LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactlyInAnyOrder(postAsset.getId(), articleAsset.getId());

        // ...and the workflow filter tells the two apart within the one Area.
        assertThat(list(new LibraryQuery(null, CONTENT_SLUG, null, null, null)))
                .extracting(LibraryAsset::assetId).containsExactly(articleAsset.getId());
        assertThat(list(new LibraryQuery(null, MARKETING_SLUG, null, null, null)))
                .extracting(LibraryAsset::assetId).containsExactly(postAsset.getId());
    }

    @Test
    void unconfirmedUploadsAreNotInTheLibrary() {
        WorkItem post = post("Launch teaser", "DRAFT");
        Asset uploaded = uploadedFile(post, "image/png", 10L);
        Asset pending = uploadedFile(post, "image/png", 10L);
        pending.setUploadStatus(AssetService.UPLOAD_STATUS_PENDING);
        pending.setDone(false);
        assetRepository.save(pending);

        assertThat(list(LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactly(uploaded.getId());
    }

    @Test
    void linkAssetsAreNotInTheLibrary() {
        WorkItem post = post("Launch teaser", "DRAFT");
        Asset file = uploadedFile(post, "image/png", 10L);

        Asset link = new Asset();
        link.setWorkItem(post);
        link.setType("published_url");
        link.setKind(AssetService.KIND_LINK);
        link.setRef("https://example.com/post");
        link.setDone(true);
        assetRepository.save(link);

        assertThat(list(LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactly(file.getId());
    }

    @Test
    void assetsFromAnotherAreaAreNotInTheLibrary() {
        Asset postAsset = uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);
        Asset prdAsset = uploadedFile(workItem("Auth PRD", "DRAFT", ENGINEERING_SLUG), "image/png", 10L);

        assertThat(list(LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactly(postAsset.getId());
        assertThat(listArea(ENGINEERING_AREA, LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactly(prdAsset.getId());
    }

    @Test
    void theAreaIsMatchedCaseInsensitivelyBecauseItArrivesAsARouteSegment() {
        Asset asset = uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);

        assertThat(listArea("marketing", LibraryQuery.unfiltered())).extracting(LibraryAsset::assetId)
                .containsExactly(asset.getId());
    }

    @Test
    void theStatusAndUploadWindowFiltersNarrowTheLibrary() {
        Asset draft = uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);
        uploadedFile(post("Launch recap", "PUBLISHED"), "image/png", 10L);

        assertThat(list(new LibraryQuery(null, null, "DRAFT", null, null)))
                .extracting(LibraryAsset::assetId).containsExactly(draft.getId());

        OffsetDateTime fiveMinutesAgo = OffsetDateTime.now().minusMinutes(5);
        assertThat(list(new LibraryQuery(null, null, null, fiveMinutesAgo, null))).hasSize(2);
        assertThat(list(new LibraryQuery(null, null, null, null, fiveMinutesAgo))).isEmpty();
    }

    @Test
    void anUnknownAreaHasAnEmptyLibraryRatherThanTheWholeProject() {
        uploadedFile(post("Launch teaser", "DRAFT"), "image/png", 10L);

        assertThat(listArea("NOT_AN_AREA", LibraryQuery.unfiltered())).isEmpty();
    }

    // --- helpers ---

    private List<LibraryAsset> list(LibraryQuery query) {
        return listArea(MARKETING_AREA, query);
    }

    private List<LibraryAsset> listArea(String area, LibraryQuery query) {
        return assetLibraryService.listAreaAssets(project.getId(), area, query, 0, 50, member);
    }

    private static LibraryQuery filter(String mediaType) {
        return new LibraryQuery(mediaType, null, null, null, null);
    }

    private static LibraryAsset rowFor(List<LibraryAsset> library, String assetId) {
        return library.stream().filter(row -> row.assetId().equals(assetId)).findFirst().orElseThrow();
    }

    private String displayId(WorkItem item) {
        return project.getKey() + "-" + item.getSequenceNumber();
    }

    private User newUser(String name) {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName(name);
        return userRepository.save(user);
    }

    /** A lifecycle Workflow header in the given Area. Only {@code definition.id} and {@code area} matter here. */
    private WorkflowDefinition workflow(String slug, String area) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setProject(project);
        definition.setName(slug);
        definition.setDefinition(objectMapper.createObjectNode().put("id", slug).put("area", area));
        definition.setArea(area);
        definition.setVersion(1);
        definition.setState("PUBLISHED");
        definition.setSchemaVersion(1);
        definition.setEnabled(true);
        return workflowDefinitionRepository.save(definition);
    }

    private WorkItem post(String title, String status) {
        return workItem(title, status, MARKETING_SLUG);
    }

    private WorkItem workItem(String title, String status, String workflowSlug) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle(title);
        item.setSequenceNumber(nextSequenceNumber++);
        item.setCreatedBy(member);
        item.setWorkflow(workflowSlug);
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        return workItemRepository.save(item);
    }

    private Asset uploadedFile(WorkItem workItem, String contentType, long sizeBytes) {
        String assetId = UUID.randomUUID().toString();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(workItem);
        asset.setType("file");
        asset.setLabel("asset-" + assetId);
        asset.setKind(AssetService.KIND_FILE);
        String gcsPath = "marketing-assets/" + project.getId() + "/" + workItem.getId() + "/" + assetId + "-media";
        asset.setRef(gcsPath);
        asset.setGcsPath(gcsPath);
        asset.setContentType(contentType);
        asset.setSizeBytes(sizeBytes);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        return assetRepository.save(asset);
    }
}
