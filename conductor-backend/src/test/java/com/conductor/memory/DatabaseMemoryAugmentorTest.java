package com.conductor.memory;

import com.conductor.agent.provider.ChatMessage;
import com.conductor.conversation.MemoryAugmentor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test (mocked {@link MemoryRetriever}/{@link AgentMemoryRepository}) for {@link DatabaseMemoryAugmentor}. */
@ExtendWith(MockitoExtension.class)
class DatabaseMemoryAugmentorTest {

    private static final String PROJECT_ID = "proj-1";
    private static final List<ChatMessage> WINDOW = List.of(ChatMessage.user("hi"));

    @Mock
    private MemoryRetriever retriever;
    @Mock
    private AgentMemoryRepository repository;

    private DatabaseMemoryAugmentor augmentor;

    @BeforeEach
    void setUp() {
        augmentor = new DatabaseMemoryAugmentor(retriever, repository, true);
    }

    private AgentMemory memory(String id, String content) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setProjectId(PROJECT_ID);
        m.setMemoryType(MemoryType.FACT);
        m.setStatus(MemoryStatus.ACTIVE);
        m.setContent(content);
        m.setImportance(5);
        m.setValidFrom(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        return m;
    }

    @Test
    void disabledSkipsRetrievalEntirely() {
        DatabaseMemoryAugmentor disabled = new DatabaseMemoryAugmentor(retriever, repository, false);

        MemoryAugmentor.Augmentation result = disabled.augment(PROJECT_ID, "agent-1", "conv-1", "what's up", WINDOW);

        assertThat(result).isEqualTo(MemoryAugmentor.Augmentation.unchanged(WINDOW));
        verifyNoInteractions(retriever);
    }

    @Test
    void blankLatestUserContentSkipsRetrieval() {
        MemoryAugmentor.Augmentation result = augmentor.augment(PROJECT_ID, "agent-1", "conv-1", "   ", WINDOW);

        assertThat(result).isEqualTo(MemoryAugmentor.Augmentation.unchanged(WINDOW));
        verifyNoInteractions(retriever);
    }

    @Test
    void noMatchesReturnsUnchanged() {
        when(retriever.retrieve(PROJECT_ID, "hello", 8)).thenReturn(List.of());

        MemoryAugmentor.Augmentation result = augmentor.augment(PROJECT_ID, "agent-1", "conv-1", "hello", WINDOW);

        assertThat(result).isEqualTo(MemoryAugmentor.Augmentation.unchanged(WINDOW));
        verify(repository, never()).bumpAccess(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void retrieverExceptionReturnsUnchanged() {
        when(retriever.retrieve(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("boom"));

        MemoryAugmentor.Augmentation result = augmentor.augment(PROJECT_ID, "agent-1", "conv-1", "hello", WINDOW);

        assertThat(result).isEqualTo(MemoryAugmentor.Augmentation.unchanged(WINDOW));
        verify(repository, never()).bumpAccess(org.mockito.ArgumentMatchers.anyCollection());
    }

    /** Three ~725-char lines against the 1,800-char budget: the first two fit (header + 2x725 ≈ 1,650),
     *  the third would push past budget and is omitted -- pins both the budget fill and the omitted-count
     *  line, and that bumpAccess only sees the ids that actually made it into the addendum. */
    @Test
    void budgetFillOmitsTrailingMemoriesAndBumpsOnlyIncludedIds() {
        AgentMemory m1 = memory("mem-1", "a".repeat(700));
        AgentMemory m2 = memory("mem-2", "b".repeat(700));
        AgentMemory m3 = memory("mem-3", "c".repeat(700));
        when(retriever.retrieve(PROJECT_ID, "hello", 8)).thenReturn(List.of(
                new MemoryRetriever.ScoredMemory(m1, 0.9, 0.9, 0.9),
                new MemoryRetriever.ScoredMemory(m2, 0.8, 0.8, 0.8),
                new MemoryRetriever.ScoredMemory(m3, 0.7, 0.7, 0.7)));

        MemoryAugmentor.Augmentation result = augmentor.augment(PROJECT_ID, "agent-1", "conv-1", "hello", WINDOW);

        assertThat(result.window()).isSameAs(WINDOW);
        assertThat(result.systemPromptAddendum())
                .contains("## Long-term memory")
                .contains("[fact · 2026-08-01]")
                .contains("a".repeat(700))
                .contains("b".repeat(700))
                .doesNotContain("c".repeat(700))
                .contains("1 more omitted for space");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(repository).bumpAccess(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("mem-1", "mem-2");
    }
}
