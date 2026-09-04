package com.conductor.v2.controller;

import com.conductor.entity.PostPublishTarget;
import com.conductor.generated.v2.model.PublishTargetResponse;
import com.conductor.service.PublishTargetService.TargetView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * One mapping from a publish target onto its wire shape, shared by every endpoint that returns one.
 *
 * <p>Three endpoints produce a {@code PublishTargetResponse} — the target listing, a manual publish, and a
 * retry — and they used to carry two near-identical copies of this mapping, which had already drifted: the
 * outcome side silently omitted {@code publishOptions}. Per-target caption and media would have made that
 * three fields to keep in sync across two copies, so the mapping lives here once and both call sites go
 * through it.
 */
final class PublishTargetResponses {

    private static final Logger log = LoggerFactory.getLogger(PublishTargetResponses.class);
    private static final ObjectMapper OPTIONS_MAPPER = new ObjectMapper();

    private PublishTargetResponses() {
    }

    static List<PublishTargetResponse> from(List<TargetView> views) {
        return views.stream().map(PublishTargetResponses::from).toList();
    }

    static PublishTargetResponse from(TargetView view) {
        PostPublishTarget target = view.target();
        return new PublishTargetResponse(
                target.getId(),
                target.getWorkItem().getId(),
                PublishTargetResponse.PlatformEnum.fromValue(target.getPlatform()),
                PublishTargetResponse.LaneEnum.fromValue(target.getLane().name()),
                target.getState().name(),
                view.effectiveAssetIds())
                // Both null on the MANUAL lane, which publishes through no connector and no account.
                .connectorId(target.getConnectorId())
                .connectionId(target.getConnectionId())
                .label(target.getPlatformAccountLabel())
                .platformPostId(target.getPlatformPostId())
                .permalink(target.getPermalink())
                .errorMessage(target.getErrorMessage())
                .fireTime(target.getFireTime())
                .publishOptions(readOptions(target.getPublishOptions()))
                .captionOverride(target.getCaptionOverride())
                // Null rather than a list when this target inherits the Post's media, so a client can render
                // "using all Post media" instead of a selection that merely happens to match today.
                .assetIds(view.assetIds())
                .effectiveCaption(view.effectiveCaption());
    }

    /**
     * The stored options bag, back on the wire as the object the client sent. Unreadable JSON reads back as
     * null rather than failing the whole listing: one corrupt row must not make a Post's targets
     * unviewable, and the row's own validator blocks approval on it anyway.
     */
    private static Map<String, Object> readOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OPTIONS_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("Unreadable publish options on a target; returning none: {}", e.toString());
            return null;
        }
    }
}
