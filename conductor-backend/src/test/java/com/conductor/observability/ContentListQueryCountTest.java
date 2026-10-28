package com.conductor.observability;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRender;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRenderRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.creative.CreativeService;
import com.conductor.creative.BrandKitService;
import com.conductor.entity.Asset;
import com.conductor.entity.DocComment;
import com.conductor.entity.DocCommentReply;
import com.conductor.entity.DocVersion;
import com.conductor.entity.Document;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageRevision;
import com.conductor.knowledge.page.KnowledgePageRevisionRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.DocCommentReplyRepository;
import com.conductor.repository.DocCommentRepository;
import com.conductor.repository.DocVersionRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.AssetService;
import com.conductor.service.DocCommentService;
import com.conductor.service.DocVersionService;
import com.conductor.service.DocumentService;
import com.conductor.service.ProjectDocService;
import com.conductor.service.view.DocumentView;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.AssetCreativeOriginResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count-constant-in-N tests for the list endpoints that used to be N+1 or to load large TEXT columns:
 * each scenario builds 1 and then 20 items in separate projects and asserts the statement count (counted by
 * the datasource proxy into {@link RequestStats}) is identical. Also pins that list payloads carry no bodies.
 */
class ContentListQueryCountTest extends AbstractNoneWebIntegrationTest {

    private static final String BIG = "x".repeat(5000);

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentService documentService;
    @Autowired private ProjectDocRepository projectDocRepository;
    @Autowired private ProjectDocService projectDocService;
    @Autowired private DocVersionRepository docVersionRepository;
    @Autowired private DocVersionService docVersionService;
    @Autowired private DocCommentRepository docCommentRepository;
    @Autowired private DocCommentReplyRepository docCommentReplyRepository;
    @Autowired private DocCommentService docCommentService;
    @Autowired private KnowledgePageRepository pageRepository;
    @Autowired private KnowledgePageRevisionRepository revisionRepository;
    @Autowired private KnowledgePageService knowledgePageService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativePhotoService photoService;
    @Autowired private BrandKitService brandKitService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private AssetService assetService;
    @Autowired private AssetCreativeOriginResolver originResolver;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.conductor.v2.controller.WorkItemListEnricher listEnricher;
    @Autowired private com.conductor.service.WorkflowSeeder workflowSeeder;
    @Autowired private com.conductor.repository.PostPublishTargetRepository targetRepository;

    private User user() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Perf User");
        return userRepository.save(u);
    }

    private Project project(User owner) {
        Project p = new Project();
        p.setName("Perf");
        p.setKey("PF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        p = projectRepository.save(p);
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(owner);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
        return p;
    }

    private WorkItem workItem(Project p, User owner) {
        WorkItem item = new WorkItem();
        item.setProject(p);
        item.setType("TASK");
        item.setTitle("Perf item");
        item.setCreatedBy(owner);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(1);
        return workItemRepository.save(item);
    }

    /** Statements issued by {@code call} (warmed once first so lazy bean/metadata init is not counted). */
    private int queries(Runnable call) {
        call.run();
        try (RequestStats.Scope scope = RequestStats.start()) {
            call.run();
            return scope.stats().dbQueries();
        }
    }

    private void assertCounts(int one, int many, String what) {
        assertThat(many).as(what + ": queries for 20 items must equal queries for 1 (was %d vs %d)", many, one)
                .isEqualTo(one);
    }

    // ── Work item documents ──────────────────────────────────────────────────────────────────────

    private WorkItem itemWithDocs(int n) {
        User u = user();
        Project p = project(u);
        WorkItem item = workItem(p, u);
        for (int i = 0; i < n; i++) {
            Document d = new Document();
            d.setWorkItem(item);
            d.setFilename("doc-" + i + ".md");
            d.setContent(BIG);
            d.setContentType("text/markdown");
            documentRepository.save(d);
        }
        return item;
    }

    /** Document reads check project membership against the authenticated caller. */
    private void signInAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void documentsListIsConstantAndCarriesNoContent() {
        WorkItem one = itemWithDocs(1);
        WorkItem many = itemWithDocs(20);
        signInAs(one.getProject().getCreatedBy());
        int q1 = queries(() -> documentService.listDocuments(one.getProject().getId(), one.getId()));
        signInAs(many.getProject().getCreatedBy());
        int q20 = queries(() -> documentService.listDocuments(many.getProject().getId(), many.getId()));
        assertCounts(q1, q20, "documents list");
        List<DocumentView> views = documentService.listDocuments(many.getProject().getId(), many.getId());
        assertThat(views).hasSize(20);
        assertThat(views).allSatisfy(v -> assertThat(v.content()).isNull());
        // The single read still carries the body.
        assertThat(documentService.getDocument(many.getProject().getId(), many.getId(), views.get(0).id()).content())
                .isEqualTo(BIG);
    }

    // ── Project docs ─────────────────────────────────────────────────────────────────────────────

    private ProjectDoc projectDoc(Project p, int i) {
        ProjectDoc d = new ProjectDoc();
        d.setProject(p);
        d.setTitle("Doc " + i);
        d.setContent(BIG);
        d.setCreatedByLabel("agent");
        d.setUpdatedByLabel("agent");
        return projectDocRepository.save(d);
    }

    @Test
    void projectDocFolderListIsConstant() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        projectDoc(p1, 0);
        for (int i = 0; i < 20; i++) {
            projectDoc(p20, i);
        }
        int q1 = queries(() -> projectDocService.getDocSummaries(p1.getId(), null, false));
        int q20 = queries(() -> projectDocService.getDocSummaries(p20.getId(), null, false));
        assertCounts(q1, q20, "project docs root list");
        int r1 = queries(() -> projectDocService.getDocSummaries(p1.getId(), null, true));
        int r20 = queries(() -> projectDocService.getDocSummaries(p20.getId(), null, true));
        assertCounts(r1, r20, "project docs recursive list");
        var rows = projectDocService.getDocSummaries(p20.getId(), null, false);
        assertThat(rows).hasSize(20);
        assertThat(rows.get(0).getProjectId()).isEqualTo(p20.getId());
        assertThat(rows.get(0).getCreatedByLabel()).isEqualTo("agent");
    }

    @Test
    void docVersionsListIsConstant() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        ProjectDoc d1 = projectDoc(p1, 0);
        ProjectDoc d20 = projectDoc(p20, 0);
        addVersions(d1, 1);
        addVersions(d20, 20);
        int q1 = queries(() -> docVersionService.listVersions(p1.getId(), d1.getId()));
        int q20 = queries(() -> docVersionService.listVersions(p20.getId(), d20.getId()));
        assertCounts(q1, q20, "doc versions list");
        var versions = docVersionService.listVersions(p20.getId(), d20.getId());
        assertThat(versions).hasSize(20);
        assertThat(versions.get(0).getVersionNumber()).isEqualTo(20);
    }

    private void addVersions(ProjectDoc doc, int n) {
        for (int i = 1; i <= n; i++) {
            DocVersion v = new DocVersion();
            v.setDoc(doc);
            v.setVersionNumber(i);
            v.setContent(BIG);
            v.setAuthorLabel("agent");
            docVersionRepository.save(v);
        }
    }

    @Test
    void docCommentsWithRepliesAreConstant() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        ProjectDoc d1 = projectDoc(p1, 0);
        ProjectDoc d20 = projectDoc(p20, 0);
        addComments(d1, u, 1);
        addComments(d20, u, 20);
        int q1 = queries(() -> docCommentService.listThreads(p1.getId(), d1.getId()));
        int q20 = queries(() -> docCommentService.listThreads(p20.getId(), d20.getId()));
        assertCounts(q1, q20, "doc comments");
        var threads = docCommentService.listThreads(p20.getId(), d20.getId());
        assertThat(threads).hasSize(20);
        assertThat(threads).allSatisfy(t -> assertThat(t.replies()).hasSize(2));
    }

    private void addComments(ProjectDoc doc, User author, int n) {
        for (int i = 0; i < n; i++) {
            DocComment c = new DocComment();
            c.setDoc(doc);
            c.setAuthor(author);
            c.setContent("comment " + i);
            c = docCommentRepository.save(c);
            for (int r = 0; r < 2; r++) {
                DocCommentReply reply = new DocCommentReply();
                reply.setComment(c);
                reply.setAuthor(author);
                reply.setContent("reply " + r);
                docCommentReplyRepository.save(reply);
            }
        }
    }

    // ── Knowledge ────────────────────────────────────────────────────────────────────────────────

    private String knowledgeProject(int pages) {
        Project p = project(user());
        for (int i = 0; i < pages; i++) {
            KnowledgePage page = new KnowledgePage();
            page.setProjectId(p.getId());
            page.setPath("area" + (i % 3) + "/page-" + i + ".md");
            page.setPageType("concept");
            page.setTitle("Page " + i);
            page.setDescription("About " + i);
            page.setFrontmatter(Map.of());
            page.setBody(BIG);
            page.setContentHash("h" + i);
            page.setVersion(1);
            page = pageRepository.save(page);
            KnowledgePageRevision rev = new KnowledgePageRevision();
            rev.setPage(page);
            rev.setVersion(1);
            rev.setBody(BIG);
            rev.setContentHash("h" + i);
            rev.setChangeKind(KnowledgePageRevision.ChangeKind.CREATE);
            revisionRepository.save(rev);
        }
        return p.getId();
    }

    @Test
    void knowledgeIndexLogAndRevisionsAreConstant() {
        String p1 = knowledgeProject(1);
        String p20 = knowledgeProject(20);
        int q1 = queries(() -> knowledgePageService.getPages(p1, List.of("index.md", "log.md")));
        int q20 = queries(() -> knowledgePageService.getPages(p20, List.of("index.md", "log.md")));
        assertCounts(q1, q20, "knowledge index+log");
        var pages = knowledgePageService.getPages(p20, List.of("index.md", "log.md"));
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).content()).contains("Page 19").doesNotContain(BIG);
        assertThat(pages.get(1).content()).contains("Create");
        assertThat(knowledgePageService.getRevisions(p20, "area0/page-0.md")).hasSize(1);
    }

    // ── Creatives ────────────────────────────────────────────────────────────────────────────────

    private CreativePhoto photo(Project p, User u) {
        CreativePhoto ph = new CreativePhoto();
        ph.setProjectId(p.getId());
        ph.setGcsPath("projects/" + p.getId() + "/photos/" + UUID.randomUUID() + ".jpg");
        ph.setContentType("image/jpeg");
        ph.setSizeBytes(1000L);
        ph.setWidth(2400);
        ph.setHeight(3000);
        ph.setSource("own");
        ph.setLicence("Own work");
        ph.setChecked(true);
        ph.setFocal(objectMapper.createObjectNode());
        ph.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        ph.setCreatedBy(u.getId());
        return photoRepository.save(ph);
    }

    private Creative creativeWithRender(Project p, User u, CreativePhoto ph, int number) {
        Creative c = new Creative();
        c.setProjectId(p.getId());
        c.setBrandKitId(brandKitService.resolveDefault(p.getId()).getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setPhotoId(ph.getId());
        c.setHeadline("Headline " + number);
        c.setBody("Body");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(u.getId());
        c.setNumber(number);
        c = creativeRepository.save(c);
        CreativeRender r = new CreativeRender();
        r.setId(UUID.randomUUID().toString());
        r.setProjectId(p.getId());
        r.setCreativeId(c.getId());
        r.setCreativeVersion(1);
        r.setState(CreativeRender.STATE_SUCCEEDED);
        r.setPreviewOnly(false);
        r.setRequestedAt(OffsetDateTime.now());
        r = renderRepository.save(r);
        for (String key : List.of("9x16", "4x5", "1x1")) {
            CreativeRenderFrame f = new CreativeRenderFrame();
            f.setId(UUID.randomUUID().toString());
            f.setRenderId(r.getId());
            f.setCreativeId(c.getId());
            f.setPlacementKey(key);
            f.setGcsPath("frames/" + UUID.randomUUID() + ".jpg");
            f.setContentType("image/jpeg");
            f.setWidth(1080);
            f.setHeight(1080);
            f.setSizeBytes(100);
            f.setWarnings(objectMapper.createArrayNode());
            f.setCreatedAt(OffsetDateTime.now());
            frameRepository.save(f);
        }
        return c;
    }

    @Test
    void creativesListIsConstant() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        CreativePhoto ph1 = photo(p1, u);
        CreativePhoto ph20 = photo(p20, u);
        creativeWithRender(p1, u, ph1, 1);
        for (int i = 1; i <= 20; i++) {
            creativeWithRender(p20, u, ph20, i);
        }
        int q1 = queries(() -> creativeService.listCreatives(p1.getId(), null, null, u));
        int q20 = queries(() -> creativeService.listCreatives(p20.getId(), null, null, u));
        assertCounts(q1, q20, "creatives list");
        var views = creativeService.listCreatives(p20.getId(), null, null, u);
        assertThat(views).hasSize(20);
        assertThat(views).allSatisfy(v -> assertThat(v.latestRenderSummary().frames()).hasSize(3));
    }

    /** Attaches one Creative-frame Asset per Creative to a single Work Item, so each Creative is "on a Post". */
    private void attachFrames(Project p, User u, List<Creative> list) {
        WorkItem item = workItem(p, u);
        for (Creative c : list) {
            CreativeRenderFrame f = frameRepository.findAllByCreativeId(c.getId()).get(0);
            Asset a = new Asset();
            a.setWorkItem(item);
            a.setType("facebook_post");
            a.setKind("file");
            a.setRef("frame-" + f.getId());
            a.setUploadStatus("UPLOADED");
            a.setContentType("image/jpeg");
            a.setSizeBytes(10L);
            a.setGcsPath(f.getGcsPath());
            a.setCreativeFrameId(f.getId());
            assetRepository.save(a);
        }
    }

    @Test
    void creativesListWithPostCountsIsConstantAndWithoutThemCostsNoMore() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        CreativePhoto ph1 = photo(p1, u);
        CreativePhoto ph20 = photo(p20, u);
        List<Creative> one = List.of(creativeWithRender(p1, u, ph1, 1));
        List<Creative> twenty = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) twenty.add(creativeWithRender(p20, u, ph20, i));
        attachFrames(p1, u, one);
        attachFrames(p20, u, twenty);

        int q1 = queries(() -> creativeService.listCreatives(p1.getId(), null, null, u, true));
        int q20 = queries(() -> creativeService.listCreatives(p20.getId(), null, null, u, true));
        assertCounts(q1, q20, "creatives list with post counts");
        assertThat(creativeService.listCreatives(p20.getId(), null, null, u, true))
                .allSatisfy(v -> assertThat(v.postCount()).isEqualTo(1));

        // The default (API/MCP) list is unchanged: same statements as before the counts existed.
        int plain20 = queries(() -> creativeService.listCreatives(p20.getId(), null, null, u));
        assertThat(plain20).isLessThan(q20);
        assertThat(creativeService.listCreatives(p20.getId(), null, null, u))
                .allSatisfy(v -> assertThat(v.postCount()).isNull());
    }

    @Test
    void postsListEnrichmentIsConstantAsPostsGrow() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        workflowSeeder.seedMarketing(p1);
        workflowSeeder.seedMarketing(p20);
        List<WorkItem> one = postsWithMedia(p1, u, 1);
        List<WorkItem> twenty = postsWithMedia(p20, u, 20);

        int q1 = queries(() -> listEnricher.enrich(p1.getId(), one));
        int q20 = queries(() -> listEnricher.enrich(p20.getId(), twenty));
        assertCounts(q1, q20, "posts list enrichment");
        assertThat(listEnricher.enrich(p20.getId(), twenty)).hasSize(20)
                .allSatisfy((id, e) -> {
                    assertThat(e.thumbnailUrl()).isNotNull();
                    assertThat(e.publishTargets()).hasSize(1);
                });
    }

    @Test
    void anEngineeringListPaysNothingForEnrichment() {
        User u = user();
        Project p = project(u);
        workflowSeeder.seedEngineering(p);
        List<WorkItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            WorkItem w = new WorkItem();
            w.setProject(p);
            w.setType("TASK");
            w.setTitle("Task " + i);
            w.setCreatedBy(u);
            w.setWorkflow("ENGINEERING");
            w.setWorkflowVersion(1);
            w.setCurrentStatus("DRAFT");
            w.setSequenceNumber(i);
            items.add(workItemRepository.save(w));
        }
        listEnricher.enrich(p.getId(), items);
        try (RequestStats.Scope scope = RequestStats.start()) {
            assertThat(listEnricher.enrich(p.getId(), items)).isEmpty();
            // Resolving the workflow may read; the Asset, target and Creative reads must not happen.
            assertThat(scope.stats().dbQueries()).isLessThanOrEqualTo(3);
        }
    }

    private List<WorkItem> postsWithMedia(Project p, User u, int n) {
        List<WorkItem> posts = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            WorkItem w = new WorkItem();
            w.setProject(p);
            w.setType("POST");
            w.setTitle("Post " + i);
            w.setCreatedBy(u);
            w.setWorkflow("MARKETING");
            w.setWorkflowVersion(1);
            w.setCurrentStatus("DRAFT");
            w.setSequenceNumber(i);
            w = workItemRepository.save(w);
            Asset a = new Asset();
            a.setWorkItem(w);
            a.setType("facebook_post");
            a.setKind("file");
            a.setRef("pic-" + i);
            a.setUploadStatus("UPLOADED");
            a.setContentType("image/png");
            a.setSizeBytes(10L);
            a.setGcsPath("assets/" + UUID.randomUUID() + ".png");
            assetRepository.save(a);
            com.conductor.entity.PostPublishTarget t = new com.conductor.entity.PostPublishTarget();
            t.setWorkItem(w);
            t.setPlatform("instagram");
            t.setLane(com.conductor.entity.PublishLane.MANUAL);
            t.setState(com.conductor.entity.PostPublishTargetState.PENDING);
            t.setIdempotencyKey("pub:" + UUID.randomUUID());
            targetRepository.save(t);
            posts.add(w);
        }
        return posts;
    }

    @Test
    void getPhotoIsConstantAsCreativesGrow() {
        User u = user();
        Project p1 = project(u);
        Project p20 = project(u);
        CreativePhoto ph1 = photo(p1, u);
        CreativePhoto ph20 = photo(p20, u);
        creativeWithRender(p1, u, ph1, 1);
        for (int i = 1; i <= 20; i++) {
            creativeWithRender(p20, u, ph20, i);
        }
        int q1 = queries(() -> photoService.getPhoto(p1.getId(), ph1.getId(), u));
        int q20 = queries(() -> photoService.getPhoto(p20.getId(), ph20.getId(), u));
        assertCounts(q1, q20, "getPhoto");
        assertThat(photoService.getPhoto(p20.getId(), ph20.getId(), u).creativeCount()).isEqualTo(20);
    }

    // ── Work item assets ─────────────────────────────────────────────────────────────────────────

    private WorkItem itemWithAssets(User u, int n) {
        Project p = project(u);
        WorkItem item = workItem(p, u);
        for (int i = 0; i < n; i++) {
            Asset a = new Asset();
            a.setWorkItem(item);
            a.setType("image");
            a.setKind("file");
            a.setRef("pic-" + i + ".png");
            a.setUploadStatus("UPLOADED");
            a.setContentType("image/png");
            a.setSizeBytes(10L);
            a.setGcsPath("assets/" + UUID.randomUUID() + ".png");
            assetRepository.save(a);
        }
        return item;
    }

    @Test
    void workItemAssetsListWithPreviewsIsConstant() {
        User u = user();
        WorkItem one = itemWithAssets(u, 1);
        WorkItem many = itemWithAssets(u, 20);
        int q1 = queries(() -> listAssetsWithPreviews(one, u));
        int q20 = queries(() -> listAssetsWithPreviews(many, u));
        assertCounts(q1, q20, "work item assets");
        List<Asset> assets = assetService.listAssets(many.getProject().getId(), many.getId(), u);
        assertThat(assetService.resolvePreviewUrls(many.getProject().getId(), many.getId(), assets)).hasSize(20);
    }

    private void listAssetsWithPreviews(WorkItem item, User u) {
        String projectId = item.getProject().getId();
        List<Asset> assets = assetService.listAssets(projectId, item.getId(), u);
        originResolver.resolve(assets);
        assetService.resolvePreviewUrls(projectId, item.getId(), assets);
    }

    // ── Migration ────────────────────────────────────────────────────────────────────────────────

    @Test
    void perfIndexMigrationAppliesAndIsIdempotent() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V151__perf_indexes.sql"),
                StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_comments_work_item_id'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
