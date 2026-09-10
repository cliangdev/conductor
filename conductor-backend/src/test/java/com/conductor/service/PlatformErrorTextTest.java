package com.conductor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers {@link PlatformErrorText} against the real Graph and TikTok error shapes the connectors produce. */
class PlatformErrorTextTest {

    @Test
    void nullAndBlankPassThroughAsNull() {
        assertThat(PlatformErrorText.humanize(null)).isNull();
        assertThat(PlatformErrorText.humanize("")).isNull();
        assertThat(PlatformErrorText.humanize("   ")).isNull();
        assertThat(PlatformErrorText.detail(null)).isNull();
        assertThat(PlatformErrorText.detail("")).isNull();
    }

    @Test
    void stripsAGraphJsonBodyAndHttpStatusFromAReadFailure() {
        String raw = "Facebook could not read post 122137176783347721: 400 {\"error\":{\"message\":"
                + "\"(#100) Tried accessing nonexisting field (status)\",\"type\":\"OAuthException\",\"code\":100}}";

        assertThat(PlatformErrorText.humanize(raw))
                .isEqualTo("Facebook could not read post 122137176783347721.");
        assertThat(PlatformErrorText.detail(raw)).isEqualTo(raw);
    }

    @Test
    void stripsAGraphJsonBodyFromAPublishRejection() {
        String raw = "Facebook rejected the publish: 400 {\"error\":{\"message\":\"Error validating access token: "
                + "Session has expired\",\"type\":\"OAuthException\",\"code\":190}}";

        assertThat(PlatformErrorText.humanize(raw)).isEqualTo("Facebook rejected the publish.");
        assertThat(PlatformErrorText.detail(raw)).isEqualTo(raw);
    }

    @Test
    void stripsAGraphJsonBodyFromAPermissionRejection() {
        String raw = "Facebook rejected the publish: 400 {\"error\":{\"message\":\"(#200) Requires "
                + "pages_manage_posts\",\"type\":\"OAuthException\",\"code\":200}}";

        assertThat(PlatformErrorText.humanize(raw)).isEqualTo("Facebook rejected the publish.");
    }

    @Test
    void keepsOnlyTheFirstSentenceOfAConfirmationTimeoutThatEmbedsAGraphError() {
        String raw = "Handed off to facebook as post 1, but it was never confirmed live after 20 checks — the "
                + "check itself failed: Facebook could not read post 1: 400 {\"error\":{\"message\":\"Unsupported "
                + "get request. Object with ID '1' does not exist, cannot be loaded due to missing permissions, "
                + "or does not support this operation.\",\"type\":\"GraphMethodException\",\"code\":100,"
                + "\"error_subcode\":33}}";

        assertThat(PlatformErrorText.humanize(raw))
                .isEqualTo("Handed off to facebook as post 1, but it was never confirmed live after 20 checks — "
                        + "the check itself failed: Facebook could not read post 1.");
        assertThat(PlatformErrorText.detail(raw)).isEqualTo(raw);
    }

    @Test
    void aTikTokBusinessErrorHasNoJsonBodyToStrip() {
        String raw = "TikTok rejected the publish permanently (spam_risk_too_many_posts): "
                + "Too many posts published today";

        assertThat(PlatformErrorText.humanize(raw))
                .isEqualTo("TikTok rejected the publish permanently (spam_risk_too_many_posts): "
                        + "Too many posts published today.");
        assertThat(PlatformErrorText.detail(raw)).isEqualTo(raw);
    }

    @Test
    void aTikTokMetricsReadFailureKeepsItsCodeAsPartOfTheSentence() {
        String raw = "TikTok refused the metrics read (video.list.oauth): the access token is invalid or has "
                + "expired (Access token invalid)";

        assertThat(PlatformErrorText.humanize(raw))
                .isEqualTo("TikTok refused the metrics read (video.list.oauth): the access token is invalid or "
                        + "has expired (Access token invalid).");
    }

    @Test
    void aTransientRateLimitMessageIsKeptWholeWithATrailingPeriod() {
        assertThat(PlatformErrorText.humanize("(#4) Application request limit reached — 429, try again later"))
                .isEqualTo("(#4) Application request limit reached — 429, try again later.");
    }

    @Test
    void aMessageAlreadyEndingWithPunctuationIsNotDoubled() {
        assertThat(PlatformErrorText.humanize("Graph API returned 500.")).isEqualTo("Graph API returned 500.");
        assertThat(PlatformErrorText.humanize("The platform said no")).isEqualTo("The platform said no.");
    }

    @Test
    void stripsATraceIdTackedOnAfterTheMessage() {
        assertThat(PlatformErrorText.humanize("Facebook rejected the publish (fbtrace_id: AbCdEf123456)"))
                .isEqualTo("Facebook rejected the publish.");
    }

    @Test
    void capsAnUnusuallyLongMessageAtTwoHundredCharacters() {
        String longSentence = "Facebook said no because " + "x".repeat(300);

        String humanized = PlatformErrorText.humanize(longSentence);

        assertThat(humanized).hasSize(PlatformErrorText.HUMANIZE_MAX_LENGTH);
        assertThat(humanized).endsWith("…");
    }

    @Test
    void detailReturnsTheFullOriginalTrimmed() {
        assertThat(PlatformErrorText.detail("  Facebook rejected the publish: 400 {}  "))
                .isEqualTo("Facebook rejected the publish: 400 {}");
    }
}
