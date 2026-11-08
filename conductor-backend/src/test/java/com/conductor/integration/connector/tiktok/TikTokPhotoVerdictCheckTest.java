package com.conductor.integration.connector.tiktok;

import com.conductor.entity.PostPublishTarget;
import com.conductor.service.ActionInvocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The checkpoint side of the late TikTok verdict check: what is pending, and releasing the links. */
class TikTokPhotoVerdictCheckTest {

    private ActionInvocationService invocations;
    private TikTokPhotoVerdictCheck check;
    private PostPublishTarget target;

    @BeforeEach
    void setUp() {
        invocations = mock(ActionInvocationService.class);
        check = new TikTokPhotoVerdictCheck(mock(TikTokClient.class), null, null, null, invocations);
        target = new PostPublishTarget();
        target.setId("t-1");
        target.setIdempotencyKey("key-1");
    }

    private void givenCheckpoint(String json) {
        when(invocations.readCheckpoint("key-1")).thenReturn(Optional.ofNullable(json));
    }

    @Test
    void pending_readsThePublishIdExpiryAndInitTimeOfAPhotoCheckpoint() {
        givenCheckpoint("{\"publishId\":\"pub-1\",\"mode\":\"PHOTO\",\"linksExpireAt\":\"2026-11-09T12:00:00Z\","
                + "\"initAt\":\"2026-11-07T12:00:00Z\"}");

        assertThat(check.pending(target)).contains(new TikTokPhotoVerdictCheck.Pending("pub-1",
                Instant.parse("2026-11-09T12:00:00Z"), Instant.parse("2026-11-07T12:00:00Z")));
    }

    @Test
    void pending_isEmptyForAVideoAReleasedCheckpointOrNoCheckpoint() {
        givenCheckpoint("{\"publishId\":\"pub-1\",\"linksExpireAt\":\"2026-11-09T12:00:00Z\"}");
        assertThat(check.pending(target)).isEmpty();
        givenCheckpoint("{\"publishId\":\"pub-1\",\"mode\":\"PHOTO\"}");
        assertThat(check.pending(target)).isEmpty();
        givenCheckpoint(null);
        assertThat(check.pending(target)).isEmpty();
        givenCheckpoint("not json");
        assertThat(check.pending(target)).isEmpty();
    }

    @Test
    void releaseLinks_clearsTheExpiryButKeepsThePublishId() {
        givenCheckpoint("{\"publishId\":\"pub-1\",\"mode\":\"PHOTO\",\"linksExpireAt\":\"2026-11-09T12:00:00Z\","
                + "\"initAt\":\"2026-11-07T12:00:00Z\"}");

        check.releaseLinks(target);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(invocations).saveCheckpoint(eq("key-1"), saved.capture());
        assertThat(saved.getValue()).contains("pub-1");
        assertThat(TikTokPublishAction.photoLinksLiveUntil(saved.getValue())).isEmpty();
    }

    @Test
    void releaseLinks_writesNothingWhenThereIsNoCheckpoint() {
        givenCheckpoint(null);

        check.releaseLinks(target);

        verify(invocations, never()).saveCheckpoint(anyString(), anyString());
    }
}
