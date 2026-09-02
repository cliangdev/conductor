package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for the file-asset upload policy — no Spring context, no mocks. */
class AssetUploadPolicyTest {

    private static Statechart chart(String resource) {
        try (InputStream in = AssetUploadPolicyTest.class.getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Statechart parse(String json) {
        try {
            return Statechart.parse(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Statechart marketing() {
        return chart("/schema/examples/marketing.workflow.json");
    }

    // --- content type allowlist -------------------------------------------------------------

    @Test
    void acceptsEveryAllowedImageAndVideoType() {
        for (String allowed : new String[] {"image/png", "image/jpeg", "image/gif", "image/webp",
                "video/mp4", "video/quicktime"}) {
            assertThat(AssetUploadPolicy.requireAllowedContentType(allowed)).isEqualTo(allowed);
        }
    }

    @Test
    void normalizesCaseAndParametersBeforeMatchingTheAllowlist() {
        assertThat(AssetUploadPolicy.requireAllowedContentType("IMAGE/PNG")).isEqualTo("image/png");
        assertThat(AssetUploadPolicy.requireAllowedContentType(" video/mp4; codecs=avc1 "))
                .isEqualTo("video/mp4");
    }

    @Test
    void rejectsDisallowedContentType() {
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedContentType("application/pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("application/pdf");
    }

    @Test
    void rejectsMissingContentType() {
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedContentType(null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedContentType("  "))
                .isInstanceOf(BusinessException.class);
    }

    // --- size ceiling -----------------------------------------------------------------------

    @Test
    void acceptsSizeUpToTheCeiling() {
        assertThat(AssetUploadPolicy.requireAllowedSize(AssetUploadPolicy.MAX_UPLOAD_BYTES))
                .isEqualTo(AssetUploadPolicy.MAX_UPLOAD_BYTES);
        assertThat(AssetUploadPolicy.requireAllowedSize(500L * 1024 * 1024)).isEqualTo(524288000L);
    }

    @Test
    void rejectsSizeAboveTheTwoGigabyteCeiling() {
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedSize(AssetUploadPolicy.MAX_UPLOAD_BYTES + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void rejectsMissingOrNonPositiveSize() {
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedSize(null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedSize(0L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AssetUploadPolicy.requireAllowedSize(-1L))
                .isInstanceOf(BusinessException.class);
    }

    // --- filename sanitization --------------------------------------------------------------

    @Test
    void keepsAlreadySafeFilenames() {
        assertThat(AssetUploadPolicy.sanitizeFilename("hero-shot_01.png")).isEqualTo("hero-shot_01.png");
    }

    @Test
    void stripsPathSeparatorsAndTraversalSegments() {
        assertThat(AssetUploadPolicy.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AssetUploadPolicy.sanitizeFilename("..\\..\\windows\\evil.png")).isEqualTo("evil.png");
        assertThat(AssetUploadPolicy.sanitizeFilename("/absolute/clip.mp4")).isEqualTo("clip.mp4");
    }

    @Test
    void sanitizedFilenameNeverContainsSeparatorsOrDotDot() {
        for (String hostile : new String[] {"../../etc/passwd", "a/../../b.png", "..\\..\\x.mp4",
                "we ird name (1).jpeg", "..hidden.png"}) {
            String safe = AssetUploadPolicy.sanitizeFilename(hostile);
            assertThat(safe).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
        }
    }

    @Test
    void replacesUnsafeCharactersWithUnderscore() {
        assertThat(AssetUploadPolicy.sanitizeFilename("we ird name (1).jpeg"))
                .isEqualTo("we_ird_name__1_.jpeg");
    }

    @Test
    void rejectsFilenamesThatSanitizeToNothing() {
        assertThatThrownBy(() -> AssetUploadPolicy.sanitizeFilename(".."))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AssetUploadPolicy.sanitizeFilename("../../"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AssetUploadPolicy.sanitizeFilename(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void truncatesOverlongFilenames() {
        String safe = AssetUploadPolicy.sanitizeFilename("a".repeat(500) + ".png");
        assertThat(safe.length()).isLessThanOrEqualTo(AssetUploadPolicy.MAX_FILENAME_LENGTH);
    }

    // --- approved-or-later predicate ----------------------------------------------------------

    @Test
    void marketingStatusesAtOrPastTheReviewGateAreApprovedOrLater() {
        Statechart marketing = marketing();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "APPROVED")).isTrue();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "SCHEDULED")).isTrue();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "PUBLISHED")).isTrue();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "FAILED")).isTrue();
    }

    @Test
    void marketingStatusesBeforeTheReviewGateAreNotApprovedOrLater() {
        Statechart marketing = marketing();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "DRAFT")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "IN_REVIEW")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "CHANGES_REQUESTED")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, null)).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "NOT_A_STATUS")).isFalse();
    }

    @Test
    void engineeringGateIsDerivedFromItsOwnDefinitionNotAHardcodedStatusList() {
        Statechart engineering = chart("/schema/examples/engineering.workflow.json");
        assertThat(AssetUploadPolicy.isApprovedOrLater(engineering, "DONE")).isTrue();
        assertThat(AssetUploadPolicy.isApprovedOrLater(engineering, "CODE_REVIEW")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(engineering, "IN_PROGRESS")).isFalse();
        // CLOSED is only reachable from pre-gate statuses, so it is not "past the gate".
        assertThat(AssetUploadPolicy.isApprovedOrLater(engineering, "CLOSED")).isFalse();
    }

    @Test
    void aWorkflowWithNoReviewGateNeverLocksAssets() {
        Statechart noGate = parse("""
                {"id":"SIMPLE","statuses":[{"id":"TODO","initial":true},{"id":"DONE","terminal":true}],
                 "transitions":[{"from":"TODO","to":"DONE"}]}
                """);
        assertThat(AssetUploadPolicy.isApprovedOrLater(noGate, "DONE")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(noGate, "TODO")).isFalse();
        assertThat(AssetUploadPolicy.reviewStatusLabel(noGate)).isEqualTo("In Review");
    }

    @Test
    void reviewStatusLabelComesFromTheDefinition() {
        assertThat(AssetUploadPolicy.reviewStatusLabel(marketing())).isEqualTo("In Review");
        assertThat(AssetUploadPolicy.reviewStatusLabel(chart("/schema/examples/engineering.workflow.json")))
                .isEqualTo("Code Review");
    }

    @Test
    void sendBackEdgeIntoTheReviewStatusDoesNotLeakPreGateStatusesIntoTheLockedSet() {
        // MARKETING declares APPROVED -> IN_REVIEW ("Send back"); walking that edge would otherwise pull
        // DRAFT / CHANGES_REQUESTED into the reachable set and lock the whole workflow.
        Statechart marketing = marketing();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "DRAFT")).isFalse();
        assertThat(AssetUploadPolicy.isApprovedOrLater(marketing, "CHANGES_REQUESTED")).isFalse();
    }
}
