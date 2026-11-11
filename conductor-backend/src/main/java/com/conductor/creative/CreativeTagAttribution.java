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

    /**
     * One resolution of a project's Creatives and the destinations they published: the Creatives themselves,
     * each one's attributed destinations, and each attributed destination's tag values. Built once per
     * request and shared by everything that needs any of the three.
     */
    public record Attribution(List<Creative> creatives, Map<String, List<PostPublishTarget>> targetsByCreative,
                              Map<String, Set<TagValue>> tagsByTarget) {}

    /** Destination id to its tag values for the whole project (see {@link #attribution}). */
    public Map<String, Set<TagValue>> tagsByTarget(String projectId) {
        return attribution(projectId, null).tagsByTarget();
    }

    /**
     * Loads the project's Creatives (only those on {@code brandKitId} when given; every Creative stores its
     * kit's real id, the default kit included, so there is no null to special-case) and resolves their
     * attributed destinations through {@link CreativeAttributionResolver}, a constant number of queries. In
     * {@code tagsByTarget} an attributed destination whose Creative has no tags maps to an empty set; a
     * destination with no attributed Creative is absent.
     */
    public Attribution attribution(String projectId, String brandKitId) {
        List<Creative> creatives = brandKitId != null
                ? creativeRepository.findAllByProjectIdAndBrandKitIdOrderByNumberDescVariantLetterAsc(projectId, brandKitId)
                : creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId);
        if (creatives.isEmpty()) {
            return new Attribution(creatives, Map.of(), Map.of());
        }
        Map<String, List<PostPublishTarget>> attributed = attributionResolver.resolvePublishedTargetsByCreative(
                creatives.stream().map(Creative::getId).toList());
        Map<String, Map<String, String>> valuesByCreative = new LinkedHashMap<>();
        for (Creative c : creatives) {
            valuesByCreative.put(c.getId(), CreativeTagsSupport.valuesOf(c.getTags()));
        }
        Map<String, Set<TagValue>> result = new LinkedHashMap<>();
        attributed.forEach((creativeId, targets) -> {
            Map<String, String> values = valuesByCreative.getOrDefault(creativeId, Map.of());
            for (PostPublishTarget target : targets) {
                Set<TagValue> set = result.computeIfAbsent(target.getId(), k -> new LinkedHashSet<>());
                values.forEach((k, v) -> set.add(new TagValue(k, v)));
            }
        });
        return new Attribution(creatives, attributed, result);
    }
}
