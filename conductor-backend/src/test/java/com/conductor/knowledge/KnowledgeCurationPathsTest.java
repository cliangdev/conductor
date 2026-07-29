package com.conductor.knowledge;

import com.conductor.knowledge.domain.KnowledgeDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link KnowledgeCurationPaths}: path shapes, and {@link
 * KnowledgeCurationPaths#forPage}'s longest-prefix-wins resolution.
 */
class KnowledgeCurationPathsTest {

    private KnowledgeDomain domain(String slug, String pathPrefix) {
        KnowledgeDomain d = new KnowledgeDomain();
        d.setSlug(slug);
        d.setPathPrefix(pathPrefix);
        return d;
    }

    @Test
    void forDomainDerivesFromPathPrefix() {
        KnowledgeDomain engineering = domain("engineering", "engineering/");

        assertThat(KnowledgeCurationPaths.forDomain(engineering)).isEqualTo("engineering/_curation.md");
    }

    @Test
    void forSlugDerivesTheSameShapeAsForDomain() {
        assertThat(KnowledgeCurationPaths.forSlug("engineering")).isEqualTo("engineering/_curation.md");
    }

    @Test
    void forPagePicksTheGoverningDomain() {
        List<KnowledgeDomain> domains = List.of(
                domain("engineering", "engineering/"),
                domain("product", "product/"));

        assertThat(KnowledgeCurationPaths.forPage("engineering/runbooks/deploy.md", domains))
                .isEqualTo("engineering/_curation.md");
    }

    @Test
    void forPageLongestPrefixWinsWhenTwoPrefixesBothMatch() {
        List<KnowledgeDomain> domains = List.of(
                domain("engineering", "engineering/"),
                domain("engineering-platform", "engineering/platform/"));

        assertThat(KnowledgeCurationPaths.forPage("engineering/platform/deploy-pipeline.md", domains))
                .isEqualTo("engineering/platform/_curation.md");

        // A sibling page under the shorter prefix still resolves to the shorter one's curation page.
        assertThat(KnowledgeCurationPaths.forPage("engineering/runbooks/deploy.md", domains))
                .isEqualTo("engineering/_curation.md");
    }

    @Test
    void forPageUnmatchedPathFallsBackToRoot() {
        List<KnowledgeDomain> domains = List.of(domain("engineering", "engineering/"));

        assertThat(KnowledgeCurationPaths.forPage("marketing/campaigns/launch.md", domains))
                .isEqualTo(KnowledgeCurationPaths.ROOT);
    }

    @Test
    void forPageRootLevelPageFallsBackToRoot() {
        List<KnowledgeDomain> domains = List.of(domain("engineering", "engineering/"));

        assertThat(KnowledgeCurationPaths.forPage("index.md", domains)).isEqualTo(KnowledgeCurationPaths.ROOT);
    }

    @Test
    void forPageEmptyDomainListFallsBackToRoot() {
        assertThat(KnowledgeCurationPaths.forPage("engineering/runbooks/deploy.md", List.of()))
                .isEqualTo(KnowledgeCurationPaths.ROOT);
    }
}
