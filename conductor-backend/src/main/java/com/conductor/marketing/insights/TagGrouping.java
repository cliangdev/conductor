package com.conductor.marketing.insights;

import com.conductor.creative.CreativeTagAttribution.TagValue;
import com.conductor.marketing.insights.PostBaselineService.Observation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns observations plus the destination-to-tags map into one bucket per (tag key, value): a {@code lessons}
 * tag ({@code #3,#12}) is split so each lesson id has its own bucket, and {@code previewRounds} is a note for
 * the author, not a dimension, so it is never grouped. A destination counts once per distinct bucket.
 */
final class TagGrouping {

    private TagGrouping() {}

    static Map<TagValue, List<Observation>> group(List<Observation> observations, Map<String, Set<TagValue>> tagsByTarget) {
        Map<TagValue, List<Observation>> grouped = new LinkedHashMap<>();
        for (Observation o : observations) {
            Set<TagValue> buckets = new LinkedHashSet<>();
            for (TagValue tag : tagsByTarget.getOrDefault(o.targetId(), Set.of())) {
                if ("previewRounds".equals(tag.key())) {
                    continue;
                }
                if ("lessons".equals(tag.key())) {
                    for (String id : tag.value().split(",")) {
                        if (!id.isBlank()) {
                            buckets.add(new TagValue("lessons", id.trim()));
                        }
                    }
                } else {
                    buckets.add(tag);
                }
            }
            for (TagValue bucket : buckets) {
                grouped.computeIfAbsent(bucket, k -> new ArrayList<>()).add(o);
            }
        }
        return grouped;
    }

    /** True when the destination's attributed Creative has a non-empty {@code lessons} tag. */
    static boolean followedLessons(String targetId, Map<String, Set<TagValue>> tagsByTarget) {
        return tagsByTarget.getOrDefault(targetId, Set.of()).stream()
                .anyMatch(t -> "lessons".equals(t.key()) && !t.value().isBlank());
    }
}
