package com.conductor.integration.connector.meta;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MetaGraphClient sends a credential with every request and builds request URIs from values that did not originate in
 * this codebase — tokens, page ids and media URLs are interpolated into Graph URLs. That makes an unvalidated host a credential-exfiltration path, not merely an unwanted
 * outbound call: whatever can influence the value receives an {@code Authorization} header. CodeQL flagged
 * this as critical server-side request forgery. These tests pin the guard, including the host-matching
 * bypasses that make a naive check useless.
 */
class MetaGraphClientSsrfGuardTest {

    @Test
    void aGraphUriIsAccepted() {
        URI graph = URI.create("https://graph.facebook.com/v21.0/me/accounts?fields=id");
        assertThat(MetaGraphClient.requireGraphUri(graph)).isEqualTo(graph);
    }

    @Test
    void aNonGraphHostIsRefused() {
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("https://evil.example.com/v21.0/me")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHostThatOnlyLooksLikeGraphIsRefused() {
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("https://graph.facebook.com.evil.example.com/v21.0/me")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("https://graph.facebook.com@evil.example.com/v21.0/me")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlaintextGraphUriIsRefused() {
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("http://graph.facebook.com/v21.0/me")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRefusalDoesNotEchoTheUri() {
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("https://evil.example.com/v21.0/me?access_token=super-secret")))
                .hasMessageNotContaining("super-secret");
    }

    // --- Same guard, for the one call that targets rupload.facebook.com instead of graph.facebook.com ---

    @Test
    void anUploadUriIsAccepted() {
        URI upload = URI.create("https://rupload.facebook.com/video-upload/v21.0/123456");
        assertThat(MetaGraphClient.requireUploadUri(upload)).isEqualTo(upload);
    }

    @Test
    void aNonUploadHostIsRefused() {
        assertThatThrownBy(() -> MetaGraphClient.requireUploadUri(
                URI.create("https://evil.example.com/video-upload/v21.0/123456")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHostThatOnlyLooksLikeTheUploadHostIsRefused() {
        assertThatThrownBy(() -> MetaGraphClient.requireUploadUri(
                URI.create("https://rupload.facebook.com.evil.example.com/video-upload/v21.0/123456")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graphIsNotAnAcceptableUploadHost_andViceVersa() {
        assertThatThrownBy(() -> MetaGraphClient.requireUploadUri(
                URI.create("https://graph.facebook.com/video-upload/v21.0/123456")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetaGraphClient.requireGraphUri(
                URI.create("https://rupload.facebook.com/v21.0/me")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
