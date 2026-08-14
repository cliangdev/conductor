package com.conductor.conversation;

import com.conductor.agent.provider.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test (no Spring) for {@link AgentConversationRunner#buildWindow}: the recent-turns window
 * policy that {@link AgentConversationRunner#runNow} hands to {@code AgentExecutionService}.
 */
class AgentConversationRunnerWindowTest {

    private ConversationMessage msg(ConversationMessage.Role role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setId("m-" + System.identityHashCode(new Object()));
        m.setConversationId("c1");
        m.setRole(role);
        m.setContent(content);
        m.setStatus(ConversationMessage.Status.COMPLETED);
        m.setCreatedAt(OffsetDateTime.now());
        return m;
    }

    private List<ConversationMessage> alternating(int count) {
        List<ConversationMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConversationMessage.Role role = i % 2 == 0 ? ConversationMessage.Role.USER : ConversationMessage.Role.ASSISTANT;
            messages.add(msg(role, role + "-" + i));
        }
        return messages;
    }

    @Test
    void emptyHistoryProducesEmptyWindow() {
        assertThat(AgentConversationRunner.buildWindow(List.of())).isEmpty();
    }

    @Test
    void withinBothCapsIncludesEverythingInOrder() {
        List<ConversationMessage> history = alternating(4); // USER, ASSISTANT, USER, ASSISTANT

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(4);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).isEqualTo("USER-0");
        assertThat(window.get(3).text()).isEqualTo("ASSISTANT-3");
    }

    @Test
    void countCapKeepsOnlyTheMostRecentTwentyAndDropsOldestFirst() {
        // 21 alternating turns (indices 0..20, USER on even indices). The last 20 is indices [1..20];
        // index 1 (ASSISTANT-1) lands at the front, so this also exercises the leading-assistant trim,
        // leaving indices [2..20] -- 19 messages, starting on USER-2.
        List<ConversationMessage> history = alternating(21);

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(19);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).isEqualTo("USER-2");
        assertThat(window.get(window.size() - 1).text()).isEqualTo("USER-20");
        // Nothing before index 2 survives (the count cap dropped index 0, the leading trim dropped 1).
        assertThat(window.stream().map(ChatMessage::text)).noneMatch(t -> t.equals("USER-0") || t.equals("ASSISTANT-1"));
    }

    @Test
    void charCapDropsOldestMessagesFirstButAlwaysKeepsTheMostRecentOne() {
        // 4 messages x 10,000 chars = 40,000 total, over the 24,000 budget. Kept from the end: "d"
        // (10,000) then "c" (20,000) -- adding "b" would push to 30,000, so it (and "a") is dropped.
        // The surviving pair already starts on USER, so this isolates the char-cap behavior from the
        // leading-assistant trim (covered separately below).
        List<ConversationMessage> history = new ArrayList<>();
        history.add(msg(ConversationMessage.Role.USER, "a".repeat(10_000)));
        history.add(msg(ConversationMessage.Role.ASSISTANT, "b".repeat(10_000)));
        history.add(msg(ConversationMessage.Role.USER, "c".repeat(10_000)));
        history.add(msg(ConversationMessage.Role.ASSISTANT, "d".repeat(10_000)));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).startsWith("c");
        assertThat(window.get(1).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(window.get(1).text()).startsWith("d");
    }

    @Test
    void charCapNeverDropsTheSingleMostRecentMessageEvenIfItAloneExceedsTheBudget() {
        List<ConversationMessage> history = List.of(msg(ConversationMessage.Role.USER, "x".repeat(30_000)));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(1);
        assertThat(window.get(0).text()).hasSize(30_000);
    }

    @Test
    void leadingAssistantAfterTrimIsDropped() {
        // ASSISTANT, USER, ASSISTANT -- if a cap trimmed off a leading USER, the window would start on
        // ASSISTANT and must be trimmed further to start on the following USER.
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.Role.ASSISTANT, "orphaned"),
                msg(ConversationMessage.Role.USER, "u1"),
                msg(ConversationMessage.Role.ASSISTANT, "a1"));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).isEqualTo("u1");
        assertThat(window.get(1).text()).isEqualTo("a1");
    }
}
