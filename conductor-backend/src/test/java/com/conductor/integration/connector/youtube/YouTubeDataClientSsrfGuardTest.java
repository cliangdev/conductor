package com.conductor.integration.connector.youtube;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * YouTubeDataClient sends a credential with every request and builds request URIs from values that did not originate in
 * this codebase — the resumable session URI is re-read from a stored resume_checkpoint so a retried upload can resume. That makes an unvalidated host a credential-exfiltration path, not merely an unwanted
 * outbound call: whatever can influence the value receives an {@code Authorization} header. CodeQL flagged
 * this as critical server-side request forgery. These tests pin the guard, including the host-matching
 * bypasses that make a naive check useless.
 */
class YouTubeDataClientSsrfGuardTest {

    @Test
    void aGoogleUploadSessionUriIsAccepted() {
        String session = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=abc";
        assertThat(YouTubeDataClient.requireGoogleUploadUri(session)).isEqualTo(URI.create(session));
        assertThatCode(() -> YouTubeDataClient.requireGoogleUploadUri(
                "https://upload.googleapis.com/upload/youtube/v3/videos?upload_id=abc"))
                .doesNotThrowAnyException();
    }

    @Test
    void aSessionUriOnAnotherHostIsRefusedBeforeTheTokenIsSent() {
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri("https://evil.example.com/upload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionUriThatOnlyLooksLikeGoogleIsRefused() {
        // The classic suffix bypass: a host that merely ends with the trusted name.
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri(
                "https://www.googleapis.com.evil.example.com/upload"))
                .isInstanceOf(IllegalArgumentException.class);
        // ...and userinfo that makes the trusted host look like a prefix.
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri(
                "https://www.googleapis.com@evil.example.com/upload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlaintextOrMalformedSessionUriIsRefused() {
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri("http://www.googleapis.com/upload"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri("not a uri"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePathAndQueryAreCarriedOverOntoTheTrustedHost() {
        assertThat(YouTubeDataClient.requireGoogleUploadUri(
                "https://upload.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=xyz"))
                .isEqualTo(URI.create(
                        "https://upload.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=xyz"));
    }

    @Test
    void theRefusalDoesNotEchoTheOffendingUri() {
        // The value is attacker-influenced in the case this guards against, and may embed a token.
        assertThatThrownBy(() -> YouTubeDataClient.requireGoogleUploadUri(
                "https://evil.example.com/upload?token=super-secret"))
                .hasMessageNotContaining("super-secret")
                .hasMessageNotContaining("evil.example.com");
    }
}
