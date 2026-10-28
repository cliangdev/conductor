package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Asset;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves, for a batch of Assets, which Creative frame each came from (asset.creative_frame_id → frame →
 * Creative). Two {@code IN} queries for the whole batch, so listing a 15-slide carousel's Assets is not N+1.
 * Read-only enrichment for {@code AssetResponse}; Assets without a frame are simply absent from the result.
 */
@Component
public class AssetCreativeOriginResolver {

    public record Origin(String placementKey, Integer sequenceIndex, String creativeId, String creativeDisplayId,
                         String creativeName) {}

    private final CreativeRenderFrameRepository frames;
    private final CreativeRepository creatives;

    public AssetCreativeOriginResolver(CreativeRenderFrameRepository frames, CreativeRepository creatives) {
        this.frames = frames;
        this.creatives = creatives;
    }

    /** Largest IN list sent in one statement (a list page can carry thousands of frame Assets). */
    static final int CHUNK = 500;

    private static java.util.List<java.util.List<String>> chunks(Collection<String> ids) {
        java.util.List<String> all = new java.util.ArrayList<>(ids);
        java.util.List<java.util.List<String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }

    /** Asset id → origin, only for Assets whose frame still exists. */
    public Map<String, Origin> resolve(Collection<Asset> assets) {
        Set<String> frameIds = new HashSet<>();
        for (Asset a : assets) {
            if (a.getCreativeFrameId() != null) frameIds.add(a.getCreativeFrameId());
        }
        if (frameIds.isEmpty()) return Map.of();

        Map<String, CreativeRenderFrame> frameById = new HashMap<>();
        for (java.util.List<String> chunk : chunks(frameIds)) {
            for (CreativeRenderFrame f : frames.findAllById(chunk)) frameById.put(f.getId(), f);
        }
        Set<String> creativeIds = new HashSet<>();
        frameById.values().forEach(f -> creativeIds.add(f.getCreativeId()));
        Map<String, Creative> creativeById = new HashMap<>();
        for (java.util.List<String> chunk : chunks(creativeIds)) {
            for (Creative c : creatives.findAllById(chunk)) creativeById.put(c.getId(), c);
        }

        Map<String, Origin> out = new HashMap<>();
        for (Asset a : assets) {
            CreativeRenderFrame f = a.getCreativeFrameId() == null ? null : frameById.get(a.getCreativeFrameId());
            if (f == null) continue;
            Creative c = creativeById.get(f.getCreativeId());
            out.put(a.getId(), new Origin(f.getPlacementKey(), f.getSequenceIndex(), f.getCreativeId(),
                    c == null ? null : c.displayId(), c == null ? null : c.getName()));
        }
        return out;
    }
}
