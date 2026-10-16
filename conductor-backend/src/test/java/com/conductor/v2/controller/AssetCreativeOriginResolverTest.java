package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Asset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetCreativeOriginResolverTest {

    private final CreativeRenderFrameRepository frames = mock(CreativeRenderFrameRepository.class);
    private final CreativeRepository creatives = mock(CreativeRepository.class);
    private final AssetCreativeOriginResolver resolver = new AssetCreativeOriginResolver(frames, creatives);

    @Test
    void resolvesFrameAndCreativeInOneBatchAndSkipsAssetsWithoutAFrame() {
        Creative c1 = new Creative();
        c1.setId("c1");
        c1.setNumber(18);
        c1.setVariantLetter("a");
        when(frames.findAllById(anyIterable()))
                .thenReturn(List.of(frame("f1", "c1", "9x16", 0), frame("f2", "c1", "4x5", null)));
        when(creatives.findAllById(anyIterable())).thenReturn(List.of(c1));

        Map<String, AssetCreativeOriginResolver.Origin> out =
                resolver.resolve(List.of(asset("a1", "f1"), asset("a2", "f2"), asset("a3", null)));

        assertThat(out).containsOnlyKeys("a1", "a2");
        assertThat(out.get("a1")).isEqualTo(new AssetCreativeOriginResolver.Origin("9x16", 0, "c1", "18a"));
        assertThat(out.get("a2").sequenceIndex()).isNull();
        verify(frames, times(1)).findAllById(anyIterable());
        verify(creatives, times(1)).findAllById(anyIterable());
    }

    @Test
    void noFramesMeansNoQueries() {
        assertThat(resolver.resolve(List.of(asset("a1", null)))).isEmpty();
        verifyNoInteractions(frames, creatives);
    }

    private static Asset asset(String id, String frameId) {
        Asset a = new Asset();
        a.setId(id);
        a.setCreativeFrameId(frameId);
        return a;
    }

    private static CreativeRenderFrame frame(String id, String creativeId, String placement, Integer seq) {
        CreativeRenderFrame f = new CreativeRenderFrame();
        f.setId(id);
        f.setCreativeId(creativeId);
        f.setPlacementKey(placement);
        f.setSequenceIndex(seq);
        return f;
    }
}
