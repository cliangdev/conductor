package com.conductor.observability;

import com.conductor.creative.BrandKit;
import com.conductor.creative.BrandKitService;
import com.conductor.creative.Creative;
import com.conductor.creative.CreativeAttachService;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativeRender;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRenderRepository;
import com.conductor.creative.CreativeRenderService;
import com.conductor.creative.CreativeRepository;
import com.conductor.creative.CreativeService;
import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.PublishTargetService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.service.publish.PublishPreflightService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count-constant-in-N tests for the slow publishing WRITE endpoints: replacing a Post's publish
 * targets (PUT), attaching a render to a Post, requesting a render, the publish preflight, Creative readiness
 * and PATCH Creative. Each endpoint is measured with a small and a large number of destinations / assets /
 * frames and the count must not grow.
 *
 * <p>{@link RequestStats#dbQueries()} counts one JDBC <em>execution</em>: a JDBC batch of N rows is one
 * (one network round trip). The {@code WRITES-QUERY-COUNTS} lines printed here are round trips. Batches are
 * chunked at {@code hibernate.jdbc.batch_size} (50) rows, so the sizes here keep each table under one chunk.
 */
class PublishWritesQueryCountTest extends AbstractNoneWebIntegrationTest {

    private static final int FEW = 1;
    private static final int MANY = 6;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private WorkItemService workItemService;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetAssetRepository targetAssetRepository;
    @Autowired private PublishTargetService publishTargetService;
    @Autowired private PublishPreflightService preflightService;
    @Autowired private com.conductor.service.PublishConsentService consentService;
    @Autowired private com.conductor.repository.PublishConsentRepository consentRepository;
    @Autowired private CreativeAttachService attachService;
    @Autowired private CreativeRenderService renderService;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private ObjectMapper objectMapper;

    private int creativeNumber = 1;

    @BeforeEach
    void bindRequest() {
        // A real request has attributes bound (the per-request membership cache lives there).
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearAuthentication() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────

    private record Fixture(User admin, Project project, List<String> connectionIds) {}

    private Fixture fixture(int connections) {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Writes User");
        u = userRepository.save(u);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        Project p = new Project();
        p.setName("Writes");
        p.setKey("WR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(u);
        p = projectRepository.save(p);
        ProjectMember m = new ProjectMember();
        m.setProject(p);
        m.setUser(u);
        m.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(m);
        workflowSeeder.seedMarketing(p);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            Connection c = new Connection();
            c.setProjectId(p.getId());
            c.setConnectorId("meta");
            c.setAuthType("OAUTH2");
            c.setStatus("ACTIVE");
            c.setConfigJson("{\"pageId\":\"p" + i + "\",\"pageName\":\"Page " + i + "\","
                    + "\"instagramBusinessAccountId\":\"ig" + i + "\",\"instagramUsername\":\"acme" + i + "\"}");
            c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
            ids.add(connectionRepository.saveAndFlush(c).getId());
        }
        return new Fixture(u, p, ids);
    }

    private WorkItem post(Fixture f) {
        return workItemService.createWorkItem(f.project().getId(), "POST", "Post " + UUID.randomUUID(), "Caption",
                "MARKETING", f.admin());
    }

    private List<Asset> assets(WorkItem post, int count) {
        List<Asset> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Asset a = new Asset();
            a.setWorkItem(post);
            a.setType("media");
            a.setKind("file");
            a.setRef("a" + i + ".jpg");
            a.setUploadStatus("UPLOADED");
            a.setContentType("image/jpeg");
            a.setSizeBytes(100L);
            a.setWidth(1080);
            a.setHeight(1350);
            a.setGcsPath("assets/" + UUID.randomUUID() + ".jpg");
            list.add(assetRepository.save(a));
        }
        return list;
    }

    private List<PublishTargetService.TargetSelection> selections(Fixture f, List<String> assetIds) {
        List<PublishTargetService.TargetSelection> list = new ArrayList<>();
        for (String connectionId : f.connectionIds()) {
            list.add(new PublishTargetService.TargetSelection("facebook", connectionId, null, null, assetIds, null));
            list.add(new PublishTargetService.TargetSelection("instagram", connectionId, null, null, assetIds, null));
        }
        return list;
    }

    private List<PublishTargetService.TargetSelection> inheriting(Fixture f) {
        List<PublishTargetService.TargetSelection> list = new ArrayList<>();
        for (String id : f.connectionIds()) {
            list.add(new PublishTargetService.TargetSelection("facebook", id));
            list.add(new PublishTargetService.TargetSelection("instagram", id));
        }
        return list;
    }

    private CreativePhoto photo(Project p, User u) {
        String id = UUID.randomUUID().toString();
        CreativePhoto ph = new CreativePhoto();
        ph.setId(id);
        ph.setProjectId(p.getId());
        ph.setGcsPath("projects/" + p.getId() + "/photos/" + id + ".jpg");
        ph.setContentType("image/jpeg");
        ph.setSizeBytes(1000L);
        ph.setWidth(1080);
        ph.setHeight(1350);
        ph.setSource("own");
        ph.setLicence("Own work");
        ph.setChecked(true);
        ph.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        ph.setFocal(objectMapper.createObjectNode());
        ph.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        ph.setCreatedBy(u.getId());
        return photoRepository.save(ph);
    }

    private Creative carousel(Fixture f, int slides) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < slides; i++) {
            ids.add(photo(f.project(), f.admin()).getId());
        }
        BrandKit kit = brandKitService.resolveDefault(f.project().getId());
        Creative c = new Creative();
        c.setProjectId(f.project().getId());
        c.setBrandKitId(kit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(Creative.KIND_CAROUSEL);
        c.setCaption("Seven ways.");
        c.setSlides(objectMapper.valueToTree(Map.of("4x5", ids)));
        c.setPlacements(objectMapper.valueToTree(List.of("4x5")));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(f.admin().getId());
        c.setNumber(creativeNumber++);
        return creativeRepository.save(c);
    }

    private Creative still(Fixture f) {
        BrandKit kit = brandKitService.resolveDefault(f.project().getId());
        Creative c = new Creative();
        c.setProjectId(f.project().getId());
        c.setBrandKitId(kit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline("Plan the week in *one sentence*.");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(f.admin().getId());
        c.setNumber(creativeNumber++);
        return creativeRepository.save(c);
    }

    private CreativeRender succeededRender(Fixture f, Creative c) {
        CreativeRender r = new CreativeRender();
        r.setProjectId(f.project().getId());
        r.setCreativeId(c.getId());
        r.setCreativeVersion(c.getVersion());
        r.setState(CreativeRender.STATE_SUCCEEDED);
        r.setPreviewOnly(false);
        r.setRequestedBy(f.admin().getId());
        return renderRepository.save(r);
    }

    private void frame(CreativeRender r, Creative c, String placement, Integer index, String platform) {
        CreativeRenderFrame fr = new CreativeRenderFrame();
        fr.setRenderId(r.getId());
        fr.setCreativeId(c.getId());
        fr.setPlacementKey(placement);
        fr.setPlatform(platform);
        fr.setSequenceIndex(index);
        fr.setGcsPath("frames/" + UUID.randomUUID() + ".jpg");
        fr.setContentType("image/jpeg");
        fr.setWidth(1080);
        fr.setHeight(1350);
        fr.setSizeBytes(100L);
        fr.setWarnings(objectMapper.createArrayNode());
        frameRepository.save(fr);
    }

    // ── measuring ──────────────────────────────────────────────────────────────────────────────────

    /** Warms up on one fresh fixture, then measures on another: a write cannot be repeated on the same rows. */
    private int measure(String what, int n, Supplier<Runnable> fresh) {
        fresh.get().run();
        Runnable call = fresh.get();
        try (RequestStats.Scope scope = RequestStats.start()) {
            call.run();
            RequestStats stats = scope.stats();
            int rows = stats.sqlCounts().values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("WRITES-DETAIL " + what + " n=" + n + ": " + stats.dbQueries() + " round trips, "
                    + rows + " statements/rows, " + stats.connCheckouts() + " connection checkouts");
            if (System.getProperty("writes.dump") != null) {
                System.out.println("WRITES-TOP " + what + " n=" + n + " " + stats.topRepeatedSql(12));
                stats.sqlCounts().forEach((sql, c) -> System.out.println(
                        "WRITES-SQL " + c + "x " + sql.substring(0, Math.min(150, sql.length()))));
            }
            return stats.dbQueries();
        }
    }

    private void assertConstant(String what, Function<Integer, Supplier<Runnable>> scenario) {
        int few = measure(what, FEW, scenario.apply(FEW));
        int many = measure(what, MANY, scenario.apply(MANY));
        System.out.println("WRITES-QUERY-COUNTS " + what + ": N=" + FEW + " -> " + few + ", N=" + MANY + " -> " + many);
        assertThat(many).as(what + ": queries for %d must equal queries for %d (was %d vs %d)", MANY, FEW, many, few)
                .isEqualTo(few);
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void replacingPublishTargetsWithOwnMediaIsConstantInTargetsAndAssets() {
        assertConstant("PUT publish-targets (create)", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = post(f);
            List<String> ids = assets(post, 3).stream().map(Asset::getId).toList();
            var selections = selections(f, ids);
            return () -> publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections, f.admin());
        });
    }

    @Test
    void reorderingPublishTargetMediaIsConstantInTargetsAndAssets() {
        assertConstant("PUT publish-targets (reorder)", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = post(f);
            List<String> ids = assets(post, 3).stream().map(Asset::getId).toList();
            publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections(f, ids), f.admin());
            List<String> reversed = new ArrayList<>(ids);
            Collections.reverse(reversed);
            var selections = selections(f, reversed);
            return () -> publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections, f.admin());
        });
    }

    @Test
    void reorderedMediaIsStoredInTheNewOrder() {
        Fixture f = fixture(2);
        WorkItem post = post(f);
        List<String> ids = assets(post, 3).stream().map(Asset::getId).toList();
        publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections(f, ids), f.admin());
        List<String> reversed = new ArrayList<>(ids);
        Collections.reverse(reversed);
        publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections(f, reversed), f.admin());
        assertThat(targetRepository.findAllByWorkItemId(post.getId())).hasSize(4);
        for (var target : targetRepository.findAllByWorkItemId(post.getId())) {
            assertThat(targetAssetRepository.findAllByTargetId(target.getId()))
                    .extracting(PostPublishTargetAsset::getAssetId).containsExactlyElementsOf(reversed);
        }
    }

    // ── behaviour: what the batched writes leave in the database ───────────────────────────────────

    /** Every stored selection of the Post, as platform+connection -> ordered asset ids. */
    private Map<String, List<String>> storedSelections(WorkItem post) {
        Map<String, List<String>> stored = new java.util.TreeMap<>();
        for (var target : targetRepository.findAllByWorkItemId(post.getId())) {
            stored.put(target.getPlatform() + "/" + target.getConnectionId(),
                    targetAssetRepository.findAllByTargetId(target.getId()).stream()
                            .map(PostPublishTargetAsset::getAssetId).toList());
        }
        return stored;
    }

    private void replace(Fixture f, WorkItem post, List<PublishTargetService.TargetSelection> selections) {
        publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections, f.admin());
    }

    private List<PublishTargetService.TargetSelection> facebookOnly(Fixture f, List<String> assetIds) {
        return f.connectionIds().stream()
                .map(id -> new PublishTargetService.TargetSelection("facebook", id, null, null, assetIds, null))
                .toList();
    }

    @Test
    void replacingMediaLeavesExactlyTheRequestedRowsForFewerMoreSameAndReordered() {
        Fixture f = fixture(2);
        WorkItem post = post(f);
        List<String> ids = assets(post, 4).stream().map(Asset::getId).toList();
        String a = ids.get(0), b = ids.get(1), c = ids.get(2), d = ids.get(3);

        for (List<String> wanted : List.of(
                List.of(a, b, c),          // create
                List.of(a, b, c),          // same: no change
                List.of(a, c),             // fewer
                List.of(a, c, b, d),       // more, new order
                List.of(d, c, b, a),       // reversed
                List.of(b, d, a, c),       // shuffled
                List.of(c))) {             // down to one
            replace(f, post, selections(f, wanted));
            Map<String, List<String>> stored = storedSelections(post);
            assertThat(stored).hasSize(4);
            assertThat(stored.values()).allSatisfy(rows -> assertThat(rows).containsExactlyElementsOf(wanted));
            // Positions are dense and zero-based, whatever the history.
            for (var target : targetRepository.findAllByWorkItemId(post.getId())) {
                assertThat(targetAssetRepository.findAllByTargetId(target.getId()))
                        .extracting(PostPublishTargetAsset::getPosition)
                        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, wanted.size()).boxed().toList());
            }
        }
    }

    @Test
    void swappingTwoPositionsDoesNotCollideOnTheUniquePositionConstraint() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        List<String> ids = assets(post, 2).stream().map(Asset::getId).toList();
        replace(f, post, selections(f, ids));

        replace(f, post, selections(f, List.of(ids.get(1), ids.get(0))));

        assertThat(storedSelections(post).values()).allSatisfy(rows ->
                assertThat(rows).containsExactly(ids.get(1), ids.get(0)));
    }

    @Test
    void removingADestinationDropsItsRowsAndLeavesTheOthersAlone() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        List<String> ids = assets(post, 2).stream().map(Asset::getId).toList();
        replace(f, post, selections(f, ids));
        String connectionId = f.connectionIds().get(0);
        var instagram = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "instagram".equals(t.getPlatform())).findFirst().orElseThrow();

        replace(f, post, facebookOnly(f, ids));

        assertThat(storedSelections(post)).containsOnlyKeys("facebook/" + connectionId);
        assertThat(storedSelections(post).get("facebook/" + connectionId)).containsExactlyElementsOf(ids);
        assertThat(targetAssetRepository.findAllByTargetId(instagram.getId())).isEmpty();
    }

    @Test
    void goingBackToInheritingClearsTheStoredRowsAndKeepsOthersUntouched() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        List<String> ids = assets(post, 2).stream().map(Asset::getId).toList();
        replace(f, post, selections(f, ids));
        String connectionId = f.connectionIds().get(0);

        replace(f, post, List.of(
                new PublishTargetService.TargetSelection("facebook", connectionId),
                new PublishTargetService.TargetSelection("instagram", connectionId, null, null, ids, null)));

        Map<String, List<String>> stored = storedSelections(post);
        assertThat(stored.get("facebook/" + connectionId)).isEmpty();
        assertThat(stored.get("instagram/" + connectionId)).containsExactlyElementsOf(ids);
    }

    @Test
    void addingADestinationInsertsItsRowsWithoutDisturbingTheExistingOnes() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        List<String> ids = assets(post, 3).stream().map(Asset::getId).toList();
        String connectionId = f.connectionIds().get(0);
        replace(f, post, facebookOnly(f, ids));

        replace(f, post, List.of(
                new PublishTargetService.TargetSelection("facebook", connectionId, null, null, ids, null),
                new PublishTargetService.TargetSelection("instagram", connectionId, null, null, List.of(ids.get(2)), null)));

        Map<String, List<String>> stored = storedSelections(post);
        assertThat(stored.get("facebook/" + connectionId)).containsExactlyElementsOf(ids);
        assertThat(stored.get("instagram/" + connectionId)).containsExactly(ids.get(2));
    }

    @Test
    void aSavedRowIsNotNewSoReSavingItUpdatesInPlaceInsteadOfInsertingOrSelecting() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        List<Asset> posted = assets(post, 2);
        replace(f, post, facebookOnly(f, List.of(posted.get(0).getId())));
        var target = targetRepository.findAllByWorkItemId(post.getId()).get(0);

        // Loaded: not new, so save() is a merge of a managed/detached row, i.e. an UPDATE, never a second INSERT.
        PostPublishTargetAsset loaded = targetAssetRepository.findById(
                new PostPublishTargetAsset.Id(target.getId(), posted.get(0).getId())).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        loaded.setPosition(7);
        try (RequestStats.Scope scope = RequestStats.start()) {
            targetAssetRepository.saveAndFlush(loaded);
            assertThat(scope.stats().sqlCounts().keySet()).noneMatch(sql -> sql.toLowerCase().startsWith("insert"));
        }
        assertThat(targetAssetRepository.findAllByTargetId(target.getId()))
                .extracting(PostPublishTargetAsset::getPosition).containsExactly(7);

        // Fresh: new, so save() persists without first SELECTing the row; afterwards it is no longer new.
        PostPublishTargetAsset added = new PostPublishTargetAsset(target.getId(), posted.get(1).getId(), 8);
        assertThat(added.isNew()).isTrue();
        try (RequestStats.Scope scope = RequestStats.start()) {
            targetAssetRepository.saveAndFlush(added);
            assertThat(scope.stats().sqlCounts().keySet())
                    .noneMatch(sql -> sql.toLowerCase().startsWith("select") && sql.contains("post_publish_target_asset"));
        }
        assertThat(added.isNew()).isFalse();
        assertThat(targetAssetRepository.findAllByTargetId(target.getId())).hasSize(2);

        // Re-saving the persisted instance (now not new) with a change is an update, not a duplicate key.
        added.setPosition(9);
        targetAssetRepository.saveAndFlush(added);
        assertThat(targetAssetRepository.findAllByTargetId(target.getId()))
                .extracting(PostPublishTargetAsset::getPosition).containsExactly(7, 9);
    }

    @Test
    void attachingACarouselCreatesOneAssetPerFrameInOrderAndReattachingReusesThem() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        replace(f, post, inheriting(f));
        Creative c = carousel(f, 1);
        CreativeRender r = succeededRender(f, c);
        for (int i = 0; i < 3; i++) {
            frame(r, c, "4x5", i, "instagram");
        }

        var first = attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());

        assertThat(first.assets()).extracting(CreativeAttachService.AttachedAsset::sequenceIndex)
                .containsExactly(0, 1, 2);
        List<Asset> onPost = assetRepository.findAllByWorkItemId(post.getId());
        assertThat(onPost).hasSize(3);
        for (var attached : first.assets()) {
            Asset asset = onPost.stream().filter(x -> x.getId().equals(attached.assetId())).findFirst().orElseThrow();
            assertThat(asset.getCreativeFrameId()).isEqualTo(attached.frameId());
            assertThat(asset.getUploadStatus()).isEqualTo("UPLOADED");
            assertThat(asset.getKind()).isEqualTo("file");
        }
        List<String> orderedIds = first.assets().stream().map(CreativeAttachService.AttachedAsset::assetId).toList();
        assertThat(storedSelections(post).get("instagram/" + f.connectionIds().get(0)))
                .containsExactlyElementsOf(orderedIds);

        var second = attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());

        assertThat(second.assets()).extracting(CreativeAttachService.AttachedAsset::assetId)
                .containsExactlyElementsOf(orderedIds);
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).hasSize(3);
    }

    @Test
    void attachingAStillCreatesOneAssetPerFrameAndFillsTheMatchingDestinations() {
        Fixture f = fixture(1);
        WorkItem post = post(f);
        replace(f, post, inheriting(f));
        Creative c = still(f);
        CreativeRender r = succeededRender(f, c);
        frame(r, c, "4x5", null, "instagram");
        frame(r, c, "1x1", null, "facebook");

        var result = attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());

        assertThat(result.assets()).hasSize(2);
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).hasSize(2);
        Map<String, String> placementByAsset = new java.util.HashMap<>();
        result.assets().forEach(x -> placementByAsset.put(x.assetId(), x.placementKey()));
        Map<String, List<String>> stored = storedSelections(post);
        String connectionId = f.connectionIds().get(0);
        assertThat(stored.get("instagram/" + connectionId)).hasSize(1)
                .allSatisfy(id -> assertThat(placementByAsset.get(id)).isEqualTo("4x5"));
        assertThat(stored.get("facebook/" + connectionId)).hasSize(1)
                .allSatisfy(id -> assertThat(placementByAsset.get(id)).isEqualTo("1x1"));
    }

    @Test
    void attachingAStillRenderIsConstantInTargets() {
        assertConstant("POST creatives/{c}/attach (targets)", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = post(f);
            publishTargetService.replaceSelection(f.project().getId(), post.getId(), inheriting(f), f.admin());
            Creative c = still(f);
            CreativeRender r = succeededRender(f, c);
            frame(r, c, "4x5", null, "instagram");
            frame(r, c, "1x1", null, "facebook");
            frame(r, c, "9x16", null, "tiktok");
            return () -> attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());
        });
    }

    @Test
    void attachingACarouselRenderIsConstantInFrames() {
        assertConstant("POST creatives/{c}/attach (frames)", n -> () -> {
            Fixture f = fixture(2);
            WorkItem post = post(f);
            publishTargetService.replaceSelection(f.project().getId(), post.getId(), inheriting(f), f.admin());
            Creative c = carousel(f, 1);
            CreativeRender r = succeededRender(f, c);
            for (int i = 0; i < 2 * n; i++) {
                frame(r, c, "4x5", i, "instagram");
            }
            return () -> attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());
        });
    }

    @Test
    void requestingACarouselRenderIsConstantInSlides() {
        assertConstant("POST creatives/{c}/renders", n -> () -> {
            Fixture f = fixture(0);
            Creative c = carousel(f, n);
            return () -> renderService.requestRender(f.project().getId(), c.getId(), new CreateCreativeRenderRequest(),
                    f.admin());
        });
    }

    @Test
    void preflightIsConstantInTargets() {
        assertConstant("GET publish-preflight", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = post(f);
            List<String> ids = assets(post, 2).stream().map(Asset::getId).toList();
            publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections(f, ids), f.admin());
            return () -> preflightService.preflight(f.project().getId(), post.getId(), f.admin());
        });
    }

    // ── publish-preflight / publish-consent: a Post with TikTok, custom media and carousel frames ─────

    private List<String> tiktokConnections(Fixture f, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Connection c = new Connection();
            c.setProjectId(f.project().getId());
            c.setConnectorId("tiktok");
            c.setAuthType("OAUTH2");
            c.setStatus("ACTIVE");
            c.setConfigJson("{\"displayName\":\"creator" + i + "\","
                    + "\"privacyLevelOptions\":[\"PUBLIC_TO_EVERYONE\",\"SELF_ONLY\"],"
                    + "\"maxVideoPostDurationSec\":600}");
            c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
            ids.add(connectionRepository.saveAndFlush(c).getId());
        }
        return ids;
    }

    /**
     * A Post with n Meta connections (Facebook + Instagram, own media), n TikTok accounts (privacy chosen),
     * a carousel attached to Instagram (frame assets, per-target selections) and a standing consent.
     */
    private WorkItem tiktokCarouselPost(Fixture f, int n) {
        WorkItem post = post(f);
        List<String> ids = assets(post, 3).stream().map(Asset::getId).toList();
        List<PublishTargetService.TargetSelection> selections = new ArrayList<>(selections(f, ids));
        for (String connectionId : tiktokConnections(f, n)) {
            selections.add(new PublishTargetService.TargetSelection("tiktok", connectionId));
        }
        publishTargetService.replaceSelection(f.project().getId(), post.getId(), selections, f.admin());
        // The privacy choices are human-only through the API (the creator's consent page writes them), so
        // the fixture sets them the way that page does: straight onto the target rows.
        for (var target : targetRepository.findAllByWorkItemId(post.getId())) {
            if ("tiktok".equals(target.getPlatform())) {
                target.setPublishOptions("{\"brandContentToggle\":false,\"brandOrganicToggle\":false,"
                        + "\"disableComment\":false,\"disableDuet\":false,\"disableStitch\":false,"
                        + "\"privacyLevel\":\"PUBLIC_TO_EVERYONE\"}");
                targetRepository.saveAndFlush(target);
            }
        }
        Creative c = carousel(f, 1);
        CreativeRender r = succeededRender(f, c);
        for (int i = 0; i < 2 * n; i++) {
            frame(r, c, "4x5", i, "instagram");
        }
        attachService.attach(f.project().getId(), c.getId(), r.getId(), post.getId(), f.admin());
        recordStandingConsent(post, f);
        return post;
    }

    private void recordStandingConsent(WorkItem post, Fixture f) {
        String hash = consentService.consentEtag(post).replace("\"", "");
        com.conductor.entity.PublishConsent consent = new com.conductor.entity.PublishConsent();
        consent.setWorkItem(post);
        consent.setConsentHash(hash);
        consent.setConsentedBy(f.admin());
        consent.setConsentedAt(java.time.OffsetDateTime.now());
        consentRepository.saveAndFlush(consent);
    }

    @Test
    void preflightWithTikTokCustomMediaAndCarouselIsConstantInTargets() {
        assertConstant("GET publish-preflight (tiktok, custom media, carousel)", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = tiktokCarouselPost(f, n);
            return () -> preflightService.preflight(f.project().getId(), post.getId(), f.admin());
        });
    }

    @Test
    void publishConsentWithTikTokCustomMediaAndCarouselIsConstantInTargets() {
        assertConstant("GET publish-consent (tiktok, custom media, carousel)", n -> () -> {
            Fixture f = fixture(n);
            WorkItem post = tiktokCarouselPost(f, n);
            return () -> consentService.readConsentWithEtag(f.project().getId(), post.getId(), f.admin());
        });
    }

    @Test
    void consentReadAgreesWithTheStandingConsentHashAndPreflightSeesItValid() {
        Fixture f = fixture(2);
        WorkItem post = tiktokCarouselPost(f, 2);

        var read = consentService.readConsentWithEtag(f.project().getId(), post.getId(), f.admin());
        assertThat(read.state().verdict()).isEqualTo(com.conductor.service.PublishConsentService.Verdict.VALID);
        assertThat(read.etag()).isEqualTo(consentService.consentEtag(post));
        assertThat(read.etag()).isEqualTo("\"" + consentRepository.findByWorkItemId(post.getId()).orElseThrow()
                .getConsentHash() + "\"");

        var preflight = preflightService.preflight(f.project().getId(), post.getId(), f.admin());
        assertThat(preflight.consent().required()).isTrue();
        assertThat(preflight.consent().verdict())
                .isEqualTo(com.conductor.service.PublishConsentService.Verdict.VALID);
    }

    @Test
    void readinessIsConstantInSlides() {
        assertConstant("GET creatives/{c}/readiness", n -> () -> {
            Fixture f = fixture(0);
            Creative c = carousel(f, n);
            return () -> creativeService.readiness(f.project().getId(), c.getId(), f.admin());
        });
    }

    @Test
    void patchingACreativeIsConstantInSlides() {
        assertConstant("PATCH creatives/{c}", n -> () -> {
            Fixture f = fixture(0);
            Creative c = carousel(f, n);
            PatchCreativeRequest patch = new PatchCreativeRequest(c.getVersion());
            patch.setCaption("A new caption.");
            return () -> creativeService.patchCreative(f.project().getId(), c.getId(), patch, f.admin());
        });
    }
}
