package com.conductor.integration.connector.tiktok;

import com.conductor.entity.PostPublishTarget;
import com.conductor.service.publish.PlatformPending;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the late TikTok verdict check reads off a waiting target. */
class TikTokPhotoVerdictCheckTest {

    private final TikTokPhotoVerdictCheck check =
            new TikTokPhotoVerdictCheck(mock(TikTokClient.class), null, null, null);

    private static PostPublishTarget target(String resumeCheckpoint) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("t-1");
        target.setResumeCheckpoint(resumeCheckpoint);
        return target;
    }

    @Test
    void pending_readsThePublishIdExpiryAndInitTimeTheOutcomeServiceStored() {
        Instant expires = Instant.parse("2026-11-09T12:00:00Z");
        Instant init = Instant.parse("2026-11-07T12:00:00Z");
        PostPublishTarget target = target(new PlatformPending("pub-1", "photo", expires, init).toJson());

        assertThat(check.pending(target)).contains(new TikTokPhotoVerdictCheck.Pending("pub-1", expires, init));
    }

    @Test
    void pending_isEmptyWithoutAMarkerOrWithAnUnreadableOne() {
        assertThat(check.pending(target(null))).isEmpty();
        assertThat(check.pending(target("not json"))).isEmpty();
        // a PERMALINK follow-up marker is not a pending post
        assertThat(check.pending(target(
                new com.conductor.service.publish.PermalinkFollowUp("pub-1", "photo").toJson()))).isEmpty();
    }

    @Test
    void platformPending_roundTripsThroughTheActionOutput() {
        Map<String, Object> output = Map.of("platform_pending", Boolean.TRUE, "publish_id", "pub-1",
                "post_kind", "photo", "links_expire_at", "2026-11-09T12:00:00Z", "init_at", "2026-11-07T12:00:00Z");

        PlatformPending pending = PlatformPending.fromOutput(output).orElseThrow();

        assertThat(PlatformPending.parse(pending.toJson())).contains(pending);
        assertThat(PlatformPending.fromOutput(Map.of("publish_id", "pub-1"))).isEmpty();
    }
}
