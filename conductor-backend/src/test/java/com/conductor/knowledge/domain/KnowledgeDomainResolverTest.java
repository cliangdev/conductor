package com.conductor.knowledge.domain;

import com.conductor.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (repository mocked) for {@link KnowledgeDomainResolver}'s precedence: explicit-valid,
 * explicit-unknown-or-inactive throws, glob pattern match, slug-order precedence under overlapping
 * globs, and the null (generalist) fallback.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDomainResolverTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeDomainRepository domainRepository;

    private KnowledgeDomainResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new KnowledgeDomainResolver(domainRepository);
    }

    private KnowledgeDomain domain(String slug, KnowledgeDomainState state, List<String> patterns) {
        KnowledgeDomain d = new KnowledgeDomain();
        d.setSlug(slug);
        d.setState(state);
        d.setSourceTypePatterns(patterns);
        return d;
    }

    @Test
    void explicitActiveDomainResolves() {
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "engineering"))
                .thenReturn(Optional.of(domain("engineering", KnowledgeDomainState.ACTIVE, List.of())));

        String resolved = resolver.resolve(PROJECT_ID, "engineering", "manual-note");

        assertThat(resolved).isEqualTo("engineering");
        verify(domainRepository, never()).findByProjectIdAndStateOrderBySlugAsc(anyString(), any());
    }

    @Test
    void explicitUnknownDomainThrows() {
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, "nonexistent", "manual-note"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void explicitInactiveDomainThrows() {
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "legal"))
                .thenReturn(Optional.of(domain("legal", KnowledgeDomainState.SUGGESTED, List.of())));

        assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, "legal", "manual-note"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void globPatternMatchesSourceType() {
        when(domainRepository.findByProjectIdAndStateOrderBySlugAsc(PROJECT_ID, KnowledgeDomainState.ACTIVE))
                .thenReturn(List.of(domain("engineering", KnowledgeDomainState.ACTIVE, List.of("github.*"))));

        String resolved = resolver.resolve(PROJECT_ID, null, "github.pr_merged");

        assertThat(resolved).isEqualTo("engineering");
    }

    @Test
    void globPatternNonMatchFallsThroughToNullLane() {
        when(domainRepository.findByProjectIdAndStateOrderBySlugAsc(PROJECT_ID, KnowledgeDomainState.ACTIVE))
                .thenReturn(List.of(domain("engineering", KnowledgeDomainState.ACTIVE, List.of("github.*"))));

        String resolved = resolver.resolve(PROJECT_ID, null, "slack-message");

        assertThat(resolved).isNull();
    }

    @Test
    void noExplicitDomainAndNoSourceTypeResolvesNullLaneWithoutQueryingRegistry() {
        String resolved = resolver.resolve(PROJECT_ID, null, null);

        assertThat(resolved).isNull();
        verify(domainRepository, never()).findByProjectIdAndStateOrderBySlugAsc(anyString(), any());
    }

    @Test
    void overlappingGlobsResolveFirstMatchInSlugOrder() {
        when(domainRepository.findByProjectIdAndStateOrderBySlugAsc(PROJECT_ID, KnowledgeDomainState.ACTIVE))
                .thenReturn(List.of(
                        domain("alpha", KnowledgeDomainState.ACTIVE, List.of("github.*")),
                        domain("beta", KnowledgeDomainState.ACTIVE, List.of("github.pr*"))));

        String resolved = resolver.resolve(PROJECT_ID, null, "github.pr_merged");

        assertThat(resolved).isEqualTo("alpha"); // first ACTIVE domain (slug order) whose pattern matches
    }

    @Test
    void wildcardOnlyPatternMatchesAnySourceType() {
        when(domainRepository.findByProjectIdAndStateOrderBySlugAsc(PROJECT_ID, KnowledgeDomainState.ACTIVE))
                .thenReturn(List.of(domain("catchall", KnowledgeDomainState.ACTIVE, List.of("*"))));

        assertThat(resolver.resolve(PROJECT_ID, null, "anything.at_all")).isEqualTo("catchall");
    }

    @Test
    void literalPatternWithRegexMetacharactersIsQuotedNotInterpretedAsRegex() {
        when(domainRepository.findByProjectIdAndStateOrderBySlugAsc(PROJECT_ID, KnowledgeDomainState.ACTIVE))
                .thenReturn(List.of(domain("finance", KnowledgeDomainState.ACTIVE, List.of("spend.report(q1)"))));

        // The literal segment contains regex metacharacters ('.', '(', ')') that must be matched
        // literally, not interpreted as regex syntax.
        assertThat(resolver.resolve(PROJECT_ID, null, "spend.report(q1)")).isEqualTo("finance");
        assertThat(resolver.resolve(PROJECT_ID, null, "spendXreportXq1X")).isNull();
    }
}
