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
        // 22 alternating turns (indices 0..21, USER on even indices, ending on ASSISTANT-21 -- a
        // trailing USER with no successor would be dropped as an orphan before the count cap even runs,
        // see orphanUserTurnAfterAFailedReplyIsDroppedToKeepStrictAlternation below; the leading-trim
        // interaction is covered separately by leadingAssistantAfterTrimIsDropped). The last 20 is
        // indices [2..21], which already starts on USER-2 -- no leading-assistant trim needed here.
        List<ConversationMessage> history = alternating(22);

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(20);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).isEqualTo("USER-2");
        assertThat(window.get(window.size() - 1).text()).isEqualTo("ASSISTANT-21");
        // Nothing before index 2 survives.
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

    /**
     * Behavior change from the strict-alternation fix: a solo message with nothing else in {@code
     * priorHistory} can only mean its own reply failed (COMPLETED-only history has no room for anything
     * else to coexist with it) -- so under strict alternation it's an orphan like any other, correctly
     * dropped entirely rather than sent to the model on its own. Before this fix, a lone oversized USER
     * message survived the char cap's "always keep at least the last item" escape hatch (still covered,
     * on a paired fixture, by charCapDropsOldestMessagesFirstButAlwaysKeepsTheMostRecentOne above); now
     * it's dropped one step earlier, before the char cap even runs.
     */
    @Test
    void charCapSoloUnpairedMessage_isDroppedAsAnOrphanRatherThanSurviving() {
        List<ConversationMessage> history = List.of(msg(ConversationMessage.Role.USER, "x".repeat(30_000)));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).isEmpty();
    }

    @Test
    void orphanUserTurnAfterAFailedReplyIsDroppedToKeepStrictAlternation() {
        // priorHistory only ever contains COMPLETED-status turns (the caller's query filters FAILED
        // out), so a failed reply leaves its USER turn with no immediate ASSISTANT successor in the raw
        // list -- exactly what a real "question failed, then retried" sequence looks like once the
        // FAILED assistant row is filtered out upstream.
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.Role.USER, "first question (its reply failed)"),
                msg(ConversationMessage.Role.USER, "retry"),
                msg(ConversationMessage.Role.ASSISTANT, "answer to retry"));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(window.get(0).text()).isEqualTo("retry");
        assertThat(window.get(1).role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(window.get(1).text()).isEqualTo("answer to retry");
    }

    @Test
    void multipleConsecutiveOrphanUserTurnsAreAllDropped() {
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.Role.USER, "attempt 1"),
                msg(ConversationMessage.Role.USER, "attempt 2"),
                msg(ConversationMessage.Role.USER, "attempt 3"),
                msg(ConversationMessage.Role.ASSISTANT, "finally answered"));

        List<ChatMessage> window = AgentConversationRunner.buildWindow(history);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).text()).isEqualTo("attempt 3");
        assertThat(window.get(1).text()).isEqualTo("finally answered");
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
