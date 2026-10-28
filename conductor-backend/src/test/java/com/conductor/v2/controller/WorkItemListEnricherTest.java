package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.StorageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
    private final com.conductor.creative.CreativeRenderFrameRepository frames =
            mock(com.conductor.creative.CreativeRenderFrameRepository.class);
    private final com.conductor.creative.CreativePhotoRepository photos =
            mock(com.conductor.creative.CreativePhotoRepository.class);
    private final WorkItemListEnricher enricher =
            new WorkItemListEnricher(assets, targets, origins, storage, frames, photos);

    @Test
    void idsUsingNarrowsByCreativeAndMediaAndIsNullWithoutFilters() {
        assertThat(enricher.idsUsing(null, " ")).isNull();

        com.conductor.creative.CreativeRenderFrame f = new com.conductor.creative.CreativeRenderFrame();
        f.setId("f1");
        when(frames.findAllByCreativeId("c1")).thenReturn(List.of(f));
        when(assets.findAllByCreativeFrameIdIn(anyCollection()))
                .thenReturn(List.of(image("a1", "w1", "p", "f1"), image("a2", "w2", "p", "f1")));
        assertThat(enricher.idsUsing("c1", null)).containsExactlyInAnyOrder("w1", "w2");

        when(assets.findAllByMediaIdFetchWorkItem("m1")).thenReturn(List.of(image("a3", "w2", "p", null)));
        when(photos.findById("m1")).thenReturn(java.util.Optional.empty());
        assertThat(enricher.idsUsing("c1", "m1")).containsExactly("w2");
    }

    @Test
    void emptyPageRunsNoQueries() {
        assertThat(enricher.enrich(List.of())).isEmpty();
        verifyNoInteractions(assets, targets, origins, storage);
    }

    @Test
    void enrichesAPageInOneQueryPerKindAndKeepsFailedTargetsVisible() {
        Asset first = image("a1", "w1", "posts/w1/first.png", "f1");
        Asset second = image("a2", "w1", "posts/w1/second.png", null);
        when(assets.findUploadedImageAssetsByWorkItemIds(anyCollection())).thenReturn(List.of(first, second));
        when(origins.resolve(anyCollection())).thenReturn(Map.of(
                "a1", new AssetCreativeOriginResolver.Origin("9x16", 0, "c1", "18a", "Bowls")));
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of(
                target("w1", "instagram", PostPublishTargetState.PUBLISHED),
                target("w1", "tiktok", PostPublishTargetState.FAILED),
                target("w2", "facebook", PostPublishTargetState.PENDING)));
        when(storage.generateSignedUrl(anyString(), anyInt())).thenAnswer(i -> "signed:" + i.getArgument(0));

        Map<String, WorkItemListEnricher.Enrichment> out = enricher.enrich(List.of("w1", "w2", "w3"));

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
        verify(assets, times(1)).findUploadedImageAssetsByWorkItemIds(anyCollection());
        verify(targets, times(1)).findAllByWorkItemIdIn(anyCollection());
        verify(storage, times(1)).generateSignedUrl(anyString(), anyInt());
    }

    @Test
    void aSigningFailureLeavesTheRowWithoutAThumbnail() {
        when(assets.findUploadedImageAssetsByWorkItemIds(anyCollection()))
                .thenReturn(List.of(image("a1", "w1", "p", null)));
        when(origins.resolve(anyCollection())).thenReturn(Map.of());
        when(targets.findAllByWorkItemIdIn(anyCollection())).thenReturn(List.of());
        when(storage.generateSignedUrl(anyString(), anyInt())).thenThrow(new IllegalStateException("no key"));

        assertThat(enricher.enrich(List.of("w1")).get("w1").thumbnailUrl()).isNull();
    }

    private static Asset image(String id, String workItemId, String path, String frameId) {
        Asset a = new Asset();
        a.setId(id);
        a.setWorkItem(item(workItemId));
        a.setGcsPath(path);
        a.setCreativeFrameId(frameId);
        return a;
    }

    private static PostPublishTarget target(String workItemId, String platform, PostPublishTargetState state) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(item(workItemId));
        t.setPlatform(platform);
        t.setState(state);
        return t;
    }

    private static WorkItem item(String id) {
        WorkItem w = new WorkItem();
        w.setId(id);
        return w;
    }
}
