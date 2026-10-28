package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.StorageService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Plain unit test (no Spring): the page is enriched in a fixed number of queries, whatever its size. */
class WorkItemListEnricherTest {

    private final AssetRepository assets = mock(AssetRepository.class);
    private final PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
    private final AssetCreativeOriginResolver origins = mock(AssetCreativeOriginResolver.class);
    private final StorageService storage = mock(StorageService.class);
    private final CreativeRenderFrameRepository frames = mock(CreativeRenderFrameRepository.class);
    private final CreativePhotoRepository photos = mock(CreativePhotoRepository.class);
    private final CreativeRepository creatives = mock(CreativeRepository.class);
    private final WorkItemWorkflowService workflows = mock(WorkItemWorkflowService.class);
    private final PublishPlatformRegistry platforms = mock(PublishPlatformRegistry.class);
    private final WorkItemListEnricher enricher = new WorkItemListEnricher(
            assets, targets, origins, storage, frames, photos, creatives, workflows, platforms);

    @Test
    void idsUsingCoversTheWholeCreativeFamilyInBatchedReadsAndNarrowsByMedia() {
        assertThat(enricher.idsUsing("p", null, " ")).isNull();

        Creative root = creative("c1", 5, "a");
        Creative variant = creative("c2", 5, "b");
        when(creatives.findById("c1")).thenReturn(Optional.of(root));
        when(creatives.findAllByNumberAndProjectId(5, "p")).thenReturn(List.of(root, variant));
        when(frames.findAllByCreativeIdIn(anyCollection())).thenReturn(List.of(frame("f1"), frame("f2")));
        when(assets.findAllByCreativeFrameIdIn(anyCollection()))
                .thenReturn(List.of(image("a1", "w1", "p", "f1"), image("a2", "w2", "p", "f2"), image("a3", "w2", "p", "f1")));
        // The root's tile counts both variants' Posts, and a Post on two members is one Post.
        assertThat(enricher.idsUsing("p", "c1", null)).containsExactlyInAnyOrder("w1", "w2");
        // One frame read for the whole family, not one per member.
        verify(frames, times(1)).findAllByCreativeIdIn(anyCollection());
        verify(frames, never()).findAllByCreativeId(anyString());

        when(assets.findAllByMediaIdFetchWorkItem("m1")).thenReturn(List.of(image("a4", "w2", "p", null)));
        when(photos.findById("m1")).thenReturn(Optional.of(photo("m1", "p")));
        assertThat(enricher.idsUsing("p", "c1", "m1")).containsExactly("w2");
    }

    @Test
    void aCreativeOrMediaFromAnotherProjectMatchesNothing() {
        Creative foreign = creative("cx", 1, "a");
        foreign.setProjectId("other");
        when(creatives.findById("cx")).thenReturn(Optional.of(foreign));
        assertThat(enricher.idsUsing("p", "cx", null)).isEmpty();
        verify(frames, never()).findAllByCreativeIdIn(anyCollection());

        when(photos.findById("mx")).thenReturn(Optional.of(photo("mx", "other")));
        assertThat(enricher.idsUsing("p", null, "mx")).isEmpty();
        verify(assets, never()).findAllByMediaIdFetchWorkItem(anyString());
    }

    @Test
    void anUnknownCreativeMatchesNothingRatherThanEverything() {
        when(creatives.findById("nope")).thenReturn(Optional.empty());
        assertThat(enricher.idsUsing("p", "nope", null)).isEmpty();
    }

    @Test
    void aLargeFamilyIsReadInBoundedChunks() {
        Creative root = creative("c1", 5, "a");
        when(creatives.findById("c1")).thenReturn(Optional.of(root));
        when(creatives.findAllByNumberAndProjectId(5, "p")).thenReturn(List.of(root));
        List<CreativeRenderFrame> many = new java.util.ArrayList<>();
        for (int i = 0; i < WorkItemListEnricher.CHUNK * 2 + 1; i++) many.add(frame("f" + i));
        when(frames.findAllByCreativeIdIn(anyCollection())).thenReturn(many);
        when(assets.findAllByCreativeFrameIdIn(anyCollection())).thenReturn(List.of());

        enricher.idsUsing("p", "c1", null);

        verify(assets, times(3)).findAllByCreativeFrameIdIn(anyCollection());
    }

    @Test
    void emptyPageRunsNoQueries() {
        assertThat(enricher.enrich("p", List.of())).isEmpty();
        verifyNoInteractions(assets, targets, origins, storage);
    }

    @Test
    void aNonPublishingWorkflowIsNotEnrichedAndPaysForNoReads() {
        when(platforms.declaresPublishing(any())).thenReturn(false);

        assertThat(enricher.enrich("p", List.of(item("w1", "ENGINEERING")))).isEmpty();

        verify(assets, never()).findFirstUploadedImagePerWorkItem(anyCollection());
        verify(targets, never()).findAllByWorkItemIdIn(anyCollection());
    }

    @Test
    void enrichesAPageInOneQueryPerKindSignsOnlyTheFirstImageAndKeepsFailedTargetsVisible() {
        Asset first = image("a1", "w1", "posts/w1/first.png", "f1");
        when(platforms.declaresPublishing(any())).thenReturn(true);
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenReturn(List.of(first));
        when(assets.findFirstCreativeFrameAssetPerWorkItem(anyCollection())).thenReturn(List.of(first));
        when(origins.resolve(anyCollection())).thenReturn(Map.of(
                "a1", new AssetCreativeOriginResolver.Origin("9x16", 0, "c1", "18a", "Bowls")));
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of(
                target("w1", "instagram", PostPublishTargetState.PUBLISHED),
                target("w1", "tiktok", PostPublishTargetState.FAILED),
                target("w2", "facebook", PostPublishTargetState.PENDING)));
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed:" + i.getArgument(0));

        Map<String, WorkItemListEnricher.Enrichment> out = enricher.enrich("p",
                List.of(item("w1", "MARKETING"), item("w2", "MARKETING"), item("w3", "MARKETING")));

        assertThat(out).containsOnlyKeys("w1", "w2");
        WorkItemListEnricher.Enrichment w1 = out.get("w1");
        assertThat(w1.thumbnailUrl()).isEqualTo("signed:posts/w1/first.png");
        assertThat(w1.creative().getDisplayId()).isEqualTo("18a");
        assertThat(w1.publishTargets()).extracting(t -> t.getPlatform() + ":" + t.getState())
                .containsExactly("instagram:PUBLISHED", "tiktok:FAILED");
        assertThat(w1.publishTargets().get(1).getStateLabel()).isEqualTo("Failed");
        WorkItemListEnricher.Enrichment w2 = out.get("w2");
        assertThat(w2.thumbnailUrl()).isNull();
        assertThat(w2.creative()).isNull();
        verify(assets, times(1)).findFirstUploadedImagePerWorkItem(anyCollection());
        verify(targets, times(1)).findAllByWorkItemIdIn(anyCollection());
        verify(storage, times(1)).generateSignedUrl(anyString(), anyInt());
    }

    @Test
    void aSigningFailureLeavesTheRowWithoutAThumbnail() {
        when(platforms.declaresPublishing(any())).thenReturn(true);
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection()))
                .thenReturn(List.of(image("a1", "w1", "p", null)));
        when(origins.resolve(anyCollection())).thenReturn(Map.of());
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        when(storage.generateSignedUrl(anyString(), anyInt())).thenThrow(new IllegalStateException("no key"));

        assertThat(enricher.enrich("p", List.of(item("w1", "MARKETING"))).get("w1").thumbnailUrl()).isNull();
    }

    @Test
    void aPostWithCreativeMediaShowsItsCreativeEvenWhenItsFirstImageIsNotFromACreative() {
        when(platforms.declaresPublishing(any())).thenReturn(true);
        Asset upload = image("a1", "w1", "posts/w1/upload.png", null);
        Asset frameAsset = image("a2", "w1", "posts/w1/frame.jpg", "f1");
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenReturn(List.of(upload));
        when(assets.findFirstCreativeFrameAssetPerWorkItem(anyCollection())).thenReturn(List.of(frameAsset));
        when(origins.resolve(anyCollection())).thenReturn(Map.of(
                "a2", new AssetCreativeOriginResolver.Origin("9x16", 0, "c1", "18a", "Bowls")));
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed:" + i.getArgument(0));

        WorkItemListEnricher.Enrichment e = enricher.enrich("p", List.of(item("w1", "MARKETING"))).get("w1");

        assertThat(e.thumbnailUrl()).isEqualTo("signed:posts/w1/upload.png");
        assertThat(e.creative().getDisplayId()).isEqualTo("18a");
    }

    @Test
    void aVideoOnlyPostFallsBackToTheMediaPoster() {
        when(platforms.declaresPublishing(any())).thenReturn(true);
        Asset video = image("a1", "w1", "posts/w1/clip.mp4", null);
        video.setMediaId("m1");
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenReturn(List.of());
        when(assets.findFirstUploadedVideoPerWorkItem(anyCollection())).thenReturn(List.of(video));
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        com.conductor.creative.CreativePhoto m = photo("m1", "p");
        m.setPosterGcsPath("media/m1-poster.jpg");
        when(photos.findAllById(anyCollection())).thenReturn(List.of(m));
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed:" + i.getArgument(0));

        assertThat(enricher.enrich("p", List.of(item("w1", "MARKETING"))).get("w1").thumbnailUrl())
                .isEqualTo("signed:media/m1-poster.jpg");
        // A Post with an image never reads video posters.
    }

    @Test
    void anImagePostNeverReadsVideoPosters() {
        when(platforms.declaresPublishing(any())).thenReturn(true);
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenReturn(List.of(image("a1", "w1", "p", null)));
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        enricher.enrich("p", List.of(item("w1", "MARKETING")));
        verify(assets, never()).findFirstUploadedVideoPerWorkItem(anyCollection());
    }

    @Test
    void publishingIsResolvedPerWorkflowVersionAndOneFailureOnlySkipsItsOwnItems() {
        WorkItem v1 = item("w1", "MARKETING");
        v1.setWorkflowVersion(1);
        WorkItem v2 = item("w2", "MARKETING");
        v2.setWorkflowVersion(2);
        when(workflows.resolveFor(any(), any())).thenAnswer(inv -> {
            WorkItem w = inv.getArgument(1);
            if (w.getWorkflowVersion() == 1) throw new IllegalStateException("broken snapshot");
            return null;
        });
        when(platforms.declaresPublishing(any())).thenReturn(true);
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.contains("w2") ? List.of(image("a2", "w2", "p2", null)) : List.of();
        });
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        when(storage.generateSignedUrl(anyString(), anyInt())).thenReturn("signed");

        Map<String, WorkItemListEnricher.Enrichment> out = enricher.enrich("p", List.of(v1, v2));

        assertThat(out).containsOnlyKeys("w2");
        verify(workflows, times(2)).resolveFor(any(), any());
    }

    @Test
    void aLongPageIsReadInBoundedChunks() {
        when(platforms.declaresPublishing(any())).thenReturn(true);
        when(assets.findFirstUploadedImagePerWorkItem(anyCollection())).thenReturn(List.of());
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        List<WorkItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < WorkItemListEnricher.CHUNK + 1; i++) items.add(item("w" + i, "MARKETING"));

        enricher.enrich("p", items);

        verify(assets, times(2)).findFirstUploadedImagePerWorkItem(anyCollection());
        verify(targets, times(2)).findAllByWorkItemIdIn(anyCollection());
    }

    private static com.conductor.creative.CreativePhoto photo(String id, String projectId) {
        com.conductor.creative.CreativePhoto p = new com.conductor.creative.CreativePhoto();
        p.setId(id);
        p.setProjectId(projectId);
        return p;
    }

    private static Creative creative(String id, int number, String letter) {
        Creative c = new Creative();
        c.setId(id);
        c.setProjectId("p");
        c.setNumber(number);
        c.setVariantLetter(letter);
        return c;
    }

    private static CreativeRenderFrame frame(String id) {
        CreativeRenderFrame f = new CreativeRenderFrame();
        f.setId(id);
        return f;
    }

    private static Asset image(String id, String workItemId, String path, String frameId) {
        Asset a = new Asset();
        a.setId(id);
        a.setWorkItem(item(workItemId, "MARKETING"));
        a.setGcsPath(path);
        a.setCreativeFrameId(frameId);
        return a;
    }

    private static PostPublishTarget target(String workItemId, String platform, PostPublishTargetState state) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(item(workItemId, "MARKETING"));
        t.setPlatform(platform);
        t.setState(state);
        return t;
    }

    private static WorkItem item(String id, String workflow) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setWorkflow(workflow);
        return w;
    }

    private static WorkItem item(String id) {
        return item(id, "MARKETING");
    }
}
