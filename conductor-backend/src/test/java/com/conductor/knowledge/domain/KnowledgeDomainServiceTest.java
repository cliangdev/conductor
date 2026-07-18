package com.conductor.knowledge.domain;

import com.conductor.agent.AgentRepository;
import com.conductor.exception.BusinessException;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (collaborators mocked) for {@link KnowledgeDomainService#suggest}'s claim-or-return
 * race handling -- specifically that a {@link DataIntegrityViolationException} thrown by the nested
 * {@code REQUIRES_NEW} insert is actually caught and turned into {@code created = false} against the
 * winning row, rather than propagating as a 500. Mocking {@code domainRepository.saveAndFlush} to throw
 * stands in for a genuine concurrent-caller race (two threads both passing the initial existence check
 * before either commits) without needing real threads or a real unique-constraint violation -- what
 * matters here is that {@link KnowledgeDomainService#insertSuggestedInNewTx} surfaces the exception to
 * its caller's try/catch, and that the catch re-reads and returns the winner.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDomainServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeDomainRepository domainRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private KnowledgePageRepository pageRepository;
    @Mock private KnowledgePageService pageService;

    private KnowledgeDomainService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDomainService(domainRepository, agentRepository, pageRepository, pageService, new ObjectMapper());
        service.self = service;
    }

    private KnowledgeDomain existingDomain(String slug) {
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setProjectId(PROJECT_ID);
        domain.setSlug(slug);
        domain.setDisplayName("Legal");
        domain.setPathPrefix(slug + "/");
        domain.setSchemaPagePath(slug + "/_schema.md");
        domain.setSourceTypePatterns(List.of());
        domain.setState(KnowledgeDomainState.SUGGESTED);
        return domain;
    }

    @Test
    void suggestCatchesRaceOnInsertAndReturnsTheWinnerWithCreatedFalse() {
        KnowledgeDomain winner = existingDomain("legal");
        // First read (before the insert attempt): no row yet -- this caller believes it's the first.
        // Second read (after the caught race): the concurrent winner's row is now visible.
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "legal"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(domainRepository.saveAndFlush(any(KnowledgeDomain.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (project_id, slug)"));

        KnowledgeDomainService.SuggestResult result =
                service.suggest(PROJECT_ID, "legal", "Legal", null, "reason", List.of(), "agent-1");

        assertThat(result.created()).isFalse();
        assertThat(result.domain()).isSameAs(winner);
    }

    @Test
    void suggestRethrowsIfTheWinnerCannotBeFoundAfterTheRace() {
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "legal")).thenReturn(Optional.empty());
        when(domainRepository.saveAndFlush(any(KnowledgeDomain.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (project_id, slug)"));

        assertThatThrownBy(() -> service.suggest(PROJECT_ID, "legal", "Legal", null, "reason", List.of(), "agent-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void suggestInsertsThroughTheSelfProxyNotDirectly() {
        when(domainRepository.findByProjectIdAndSlug(PROJECT_ID, "legal")).thenReturn(Optional.empty());
        when(domainRepository.saveAndFlush(any(KnowledgeDomain.class))).thenAnswer(inv -> inv.getArgument(0));

        service.suggest(PROJECT_ID, "legal", "Legal", null, "reason", List.of(), "agent-1");

        verify(domainRepository).saveAndFlush(any(KnowledgeDomain.class));
        verify(domainRepository, never()).save(any(KnowledgeDomain.class));
    }

    @Test
    void suggestRejectsSlugExceedingColumnWidth() {
        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> service.suggest(PROJECT_ID, tooLong, "Legal", null, "reason", List.of(), "agent-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void suggestRejectsDisplayNameExceedingColumnWidth() {
        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> service.suggest(PROJECT_ID, "legal", tooLong, null, "reason", List.of(), "agent-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void suggestRejectsNullElementInSourceTypePatterns() {
        List<String> patterns = new java.util.ArrayList<>();
        patterns.add(null);
        assertThatThrownBy(() -> service.suggest(PROJECT_ID, "legal", "Legal", null, "reason", patterns, "agent-1"))
                .isInstanceOf(BusinessException.class);
        verify(domainRepository, never()).saveAndFlush(any());
    }

    @Test
    void suggestRejectsBlankElementInSourceTypePatterns() {
        assertThatThrownBy(() -> service.suggest(PROJECT_ID, "legal", "Legal", null, "reason", List.of("  "), "agent-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateRejectsDisplayNameExceedingColumnWidth() {
        String tooLong = "a".repeat(256);
        assertThatThrownBy(() -> service.update(PROJECT_ID, "legal", tooLong, null, null, null))
                .isInstanceOf(BusinessException.class);
        verify(domainRepository, never()).findByProjectIdAndSlug(eq(PROJECT_ID), eq("legal"));
    }

    @Test
    void updateRejectsNullElementInSourceTypePatterns() {
        List<String> patterns = new java.util.ArrayList<>();
        patterns.add("github.*");
        patterns.add(null);
        assertThatThrownBy(() -> service.update(PROJECT_ID, "legal", null, null, patterns, null))
                .isInstanceOf(BusinessException.class);
    }
}
