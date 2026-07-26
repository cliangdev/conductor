package com.conductor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the round trip that keeps embedded doc images alive: a signed URL written in becomes a stable
 * marker, and a marker read out becomes a freshly signed URL.
 */
class DocImageMarkersTest {

    private static final String PATH = "projects/proj-1/docs/doc-1/images/abc.png";
    private static final DocImageMarkers.UrlSigner SIGNER = p -> "https://signed.test/" + p + "?sig=fresh";

    @Test
    void normalizesAGcsSignedUrlToAMarker() {
        String content = "![shot](https://storage.googleapis.com/bucket/" + PATH
                + "?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Expires=900)";

        assertThat(DocImageMarkers.normalize(content)).isEqualTo("![shot](conductor-image:" + PATH + ")");
    }

    @Test
    void normalizesALocalStorageUrlToTheSameMarker() {
        String content = "![shot](http://localhost:8080/api/v1/local-files/" + PATH + ")";

        assertThat(DocImageMarkers.normalize(content)).isEqualTo("![shot](conductor-image:" + PATH + ")");
    }

    @Test
    void isIdempotentSoRepeatedSavesDoNotDrift() {
        String once = DocImageMarkers.normalize("![s](https://storage.googleapis.com/b/" + PATH + "?sig=1)");

        assertThat(DocImageMarkers.normalize(once)).isEqualTo(once);
    }

    @Test
    void leavesUnrelatedLinksAlone() {
        String content = "[docs](https://example.com/a) and ![ext](https://cdn.example.com/pic.png)";

        assertThat(DocImageMarkers.normalize(content)).isEqualTo(content);
    }

    @Test
    void rendersAMarkerAsAFreshlySignedUrl() {
        String stored = "![shot](conductor-image:" + PATH + ")";

        assertThat(DocImageMarkers.render(stored, "proj-1", SIGNER))
                .isEqualTo("![shot](https://signed.test/" + PATH + "?sig=fresh)");
    }

    @Test
    void roundTripsBackToTheSameStoredForm() {
        String stored = "![shot](conductor-image:" + PATH + ")";

        String rendered = DocImageMarkers.render(stored, "proj-1", SIGNER);

        // What a client reads and writes straight back must land byte-identical in storage — otherwise
        // an agent that reads a doc and rewrites it re-bakes an expiring URL.
        assertThat(DocImageMarkers.normalize(rendered)).isEqualTo(stored);
    }

    @Test
    void refusesToSignAMarkerPointingOutsideThisProject() {
        String hostile = "![x](conductor-image:projects/other-project/docs/doc-9/images/secret.png)";

        // Markdown is user-authored, so a marker is attacker-controlled text: signing it blindly would
        // mint a working URL for another project's object.
        assertThat(DocImageMarkers.render(hostile, "proj-1", SIGNER)).isEqualTo(hostile);
    }

    @Test
    void handlesNullAndEmptyContent() {
        assertThat(DocImageMarkers.normalize(null)).isNull();
        assertThat(DocImageMarkers.render(null, "proj-1", SIGNER)).isNull();
        assertThat(DocImageMarkers.normalize("")).isEmpty();
        assertThat(DocImageMarkers.render("", "proj-1", SIGNER)).isEmpty();
    }

    @Test
    void rendersEveryMarkerInADocument() {
        String stored = "![a](conductor-image:projects/proj-1/docs/d/images/1.png)\n"
                + "![b](conductor-image:projects/proj-1/docs/d/images/2.png)";

        String rendered = DocImageMarkers.render(stored, "proj-1", SIGNER);

        assertThat(rendered).doesNotContain(DocImageMarkers.SCHEME);
        assertThat(rendered).contains("1.png?sig=fresh").contains("2.png?sig=fresh");
    }
}
