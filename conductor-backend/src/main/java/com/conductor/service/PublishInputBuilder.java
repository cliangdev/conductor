package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.service.publish.PublishPlatform;
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

        input.put("work_item_id", post.getId());
        input.put("target_id", target.getId());
        return input;
    }
}
