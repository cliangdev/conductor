package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tag values apply to each published destination: the tags of every Creative a destination was
 * published from, resolved through {@link CreativeAttributionResolver} (never a second join). A destination
 * attributed to several tagged Creatives carries each distinct (key, value) once; one with no attributed
 * Creative, or only untagged ones, is absent from the result. Read by marketing insights' {@code byTag} and
 * the weekly snapshot's {@code byHookType}.
 */
@Component
public class CreativeTagAttribution {

    /** One tag key and its value. */
    public record TagValue(String key, String value) {}

    private final CreativeRepository creativeRepository;
    private final CreativeAttributionResolver attributionResolver;

    public CreativeTagAttribution(CreativeRepository creativeRepository,
                                  CreativeAttributionResolver attributionResolver) {
        this.creativeRepository = creativeRepository;
        this.attributionResolver = attributionResolver;
    }

    /** Destination id to its tag values, for every Creative of the project (an attributed destination whose Creative has no tags maps to an empty set; a destination with no attributed Creative is absent) (a constant number of queries). */
    public Map<String, Set<TagValue>> tagsByTarget(String projectId) {
        Map<String, Map<String, String>> tagsByCreative = new LinkedHashMap<>();
        for (Creative c : creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId)) {
            // Untagged Creatives are kept (with no values): a destination attributed to one is present in the
            // result with an empty set, which tells it apart from a destination with no Creative at all.
            tagsByCreative.put(c.getId(), CreativeTagsSupport.valuesOf(c.getTags()));
        }
        if (tagsByCreative.isEmpty()) {
            return Map.of();
        }
        Map<String, List<PostPublishTarget>> attributed =
                attributionResolver.resolvePublishedTargetsByCreative(tagsByCreative.keySet());
        Map<String, Set<TagValue>> result = new LinkedHashMap<>();
        attributed.forEach((creativeId, targets) -> {
            Map<String, String> values = tagsByCreative.get(creativeId);
            for (PostPublishTarget target : targets) {
                Set<TagValue> set = result.computeIfAbsent(target.getId(), k -> new LinkedHashSet<>());
                values.forEach((k, v) -> set.add(new TagValue(k, v)));
            }
        });
        return result;
    }
}
