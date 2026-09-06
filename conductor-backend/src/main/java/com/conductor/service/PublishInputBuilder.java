package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishPlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The publish payload every dispatcher hands a platform action: what to say, and which media to say it with.
 *
 * <p>Two callers dispatch a publish — {@code PostPublishScheduler} for the APP_MANAGED lane and
 * {@code NativeHandoffService} for the NATIVE one — and they each built this map themselves, in near-identical
 * code. That duplication is precisely where the copy that gets approved and the copy that gets published can
 * drift apart, so both now come through here and add only their own lane-specific extras (publish options, or
 * the platform's schedule parameter).
 *
 * <p>{@code asset_ids} is always present, ordered, and possibly empty. Passing the resolved ids rather than
 * letting each publisher re-derive them from the Work Item is the point: the approval gate validated a
 * specific ordered set for this destination, and the publisher must send that set — not whatever the Post
 * happens to carry at fire time.
 */
@Component
public class PublishInputBuilder {

    /** Media parameter every platform publisher reads its ordered selection from. */
    public static final String INPUT_ASSET_IDS = "asset_ids";
    /** The target's {@link PostFormat}, lowercase: {@code feed}, {@code reel} or {@code story}. */
    public static final String INPUT_FORMAT = "format";
    private static final Logger log = LoggerFactory.getLogger(PublishInputBuilder.class);
    private static final ObjectMapper OPTIONS_MAPPER = new ObjectMapper();

    private final PublishPlatformRegistry platformRegistry;
    private final PublishTargetMediaResolver mediaResolver;

    public PublishInputBuilder(PublishPlatformRegistry platformRegistry, PublishTargetMediaResolver mediaResolver) {
        this.platformRegistry = platformRegistry;
        this.mediaResolver = mediaResolver;
    }

    /**
     * @param captionParam the action's own name for the post's body text ({@code message} on Facebook,
     *                     {@code caption} on Instagram, {@code description} on YouTube, {@code title} on a
     *                     TikTok video)
     */
    public Map<String, Object> build(PostPublishTarget target, WorkItem post, String captionParam) {
        Map<String, Object> input = new LinkedHashMap<>();
        String caption = mediaResolver.effectiveCaption(target, post);
        if (caption != null) {
            input.put(captionParam, caption);
        }
        if (post.getTitle() != null && !"title".equals(captionParam)) {
            input.put("title", post.getTitle());
        }

        List<Asset> media = mediaResolver.effectiveMedia(target).assets();
        input.put(INPUT_ASSET_IDS, media.stream().map(Asset::getId).toList());

        // A platform may name the same copy under more parameters than one. TikTok is the case in point: a
        // video post has a single title, a photo post has both a title and a longer description, so it
        // declares `description` and `headline` as aliases and the action picks without having to reach
        // back for the Work Item.
        platformRegistry.find(target.getPlatform()).ifPresent(platform ->
                platform.publish().copyAliases().forEach((param, source) -> {
                    String value = source == PublishPlatform.CopySource.TITLE ? post.getTitle() : caption;
                    if (value != null) {
                        input.put(param, value);
                    }
                }));

        // The shape this destination publishes in, for a connector that branches on it (a story, a reel).
        input.put(INPUT_FORMAT, PostFormat.parse(target.getFormat()).wire());
        // The per-target option bag, each key renamed to the parameter the connector's tool spec declares
        // (PublishPlatform#optionParams). Shared by both lanes: the APP_MANAGED dispatch and the NATIVE
        // hand-off build their input here, so a YouTube option reaches the platform the same way a TikTok
        // one does.
        platformRegistry.find(target.getPlatform()).ifPresent(platform ->
                input.putAll(publishOptions(target, platform)));
        input.put("work_item_id", post.getId());
        input.put("target_id", target.getId());
        return input;
    }

    /**
     * The row's {@code publishOptions} bag as connector input: only keys the platform declares, renamed
     * to the spec's parameter names, in the spec's order. An unreadable bag publishes without options and
     * logs loudly, because a stored choice is being ignored and the row is visible to a human.
     */
    Map<String, Object> publishOptions(PostPublishTarget target, PublishPlatform platform) {
        Map<String, String> params = platform.optionParams();
        String json = target.getPublishOptions();
        if (params.isEmpty() || json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode options;
        try {
            options = OPTIONS_MAPPER.readTree(json);
        } catch (Exception e) {
            log.error("Unreadable publish options on target {}; publishing without them: {}",
                    target.getId(), e.toString());
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        params.forEach((key, param) -> {
            JsonNode value = options.get(key);
            if (value != null && !value.isNull()) {
                mapped.put(param, OPTIONS_MAPPER.convertValue(value, Object.class));
            }
        });
        return mapped;
    }
}
