package com.conductor.comment;

import com.conductor.entity.User;
import com.conductor.repository.UserRepository;
import com.conductor.service.EmailService;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two subscribers of {@code conductor.comment.mentioned}: the agent dispatch (humans only, after commit) and
 * the mention email (not the author, after commit). A transaction is simulated with the synchronization manager,
 * so "a rolled-back comment does nothing" is tested for real rather than assumed.
 */
class CommentMentionSubscribersTest {

    CommentAgentDispatchService dispatchService = mock(CommentAgentDispatchService.class);
    CommentAgentDispatchSubscriber dispatchSubscriber = new CommentAgentDispatchSubscriber(dispatchService);

    UserRepository users = mock(UserRepository.class);
    EmailService email = mock(EmailService.class);
    CommentMentionEmailSubscriber emailSubscriber = new CommentMentionEmailSubscriber(users, email);

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Signal mentioned(String authorKind, String authorId, List<String> userIds, List<String> agentIds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", "p1");
        payload.put("commentId", "c1");
        payload.put("authorName", "Ann");
        payload.put("authorKind", authorKind);
        if (authorId != null) {
            payload.put("authorId", authorId);
        }
        payload.put("documentTitle", "PRD / spec.md");
        payload.put("excerpt", "tighten this");
        payload.put("link", "http://x/app/projects/p1/comments?comment=c1");
        payload.put("userIds", userIds);
        payload.put("agentIds", agentIds);
        return Signal.of(SignalTypes.CONDUCTOR_COMMENT_MENTIONED, "p1", "c1", Instant.now(), payload,
                new SignalOrigin("comment", "c1"));
    }

    private static void commit() {
        for (TransactionSynchronization s : new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            s.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void rollback() {
        for (TransactionSynchronization s : new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ---- agent dispatch ----

    @Test
    void aHumanMentionDispatchesOnlyAfterTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        dispatchSubscriber.onSignal(mentioned("USER", "u1", List.of(), List.of("agent-1")));
        verify(dispatchService, never()).dispatch(any(), any(), any(), any());

        commit();
        verify(dispatchService).dispatch("p1", "c1", List.of("agent-1"), "Ann");
    }

    @Test
    void aRolledBackCommentNeverDispatches() {
        TransactionSynchronizationManager.initSynchronization();

        dispatchSubscriber.onSignal(mentioned("USER", "u1", List.of(), List.of("agent-1")));
        rollback();

        verify(dispatchService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void textAnAgentWroteNeverDispatches() {
        dispatchSubscriber.onSignal(mentioned("AGENT", null, List.of(), List.of("agent-1")));

        verify(dispatchService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void aMentionOfOnlyPeopleDispatchesNothing() {
        dispatchSubscriber.onSignal(mentioned("USER", "u1", List.of("u2"), List.of()));

        verify(dispatchService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void withNoTransactionItDispatchesImmediately() {
        dispatchSubscriber.onSignal(mentioned("USER", "u1", List.of(), List.of("agent-1", "agent-2")));

        verify(dispatchService).dispatch("p1", "c1", List.of("agent-1", "agent-2"), "Ann");
    }

    @Test
    void aFailingDispatchNeverReachesTheCommitter() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(dispatchService)
                .dispatch(any(), any(), any(), any());
        TransactionSynchronizationManager.initSynchronization();

        dispatchSubscriber.onSignal(mentioned("USER", "u1", List.of(), List.of("agent-1")));

        commit(); // would throw if the subscriber let it escape
    }

    @Test
    void onlyTheMentionSignalIsOfInterest() {
        assertThat(dispatchSubscriber.interestedIn(SignalTypes.CONDUCTOR_COMMENT_MENTIONED)).isTrue();
        assertThat(dispatchSubscriber.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED)).isFalse();
        assertThat(emailSubscriber.interestedIn(SignalTypes.CONDUCTOR_COMMENT_MENTIONED)).isTrue();
        assertThat(emailSubscriber.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED)).isFalse();
    }

    // ---- mention email ----

    @Test
    void eachMentionedMemberIsEmailedButNeverTheAuthor() {
        User bob = user("u2", "bob@x.test");
        when(users.findById("u2")).thenReturn(Optional.of(bob));

        emailSubscriber.onSignal(mentioned("USER", "u1", List.of("u1", "u2"), List.of()));

        verify(email).sendMentionEmail(eq(bob), eq("Ann"), eq("PRD / spec.md"), eq("tighten this"),
                eq("http://x/app/projects/p1/comments?comment=c1"));
        verify(users, never()).findById("u1");
    }

    @Test
    void mentioningOnlyYourselfSendsNothing() {
        emailSubscriber.onSignal(mentioned("USER", "u1", List.of("u1"), List.of()));

        verify(email, never()).sendMentionEmail(any(), any(), any(), any(), any());
    }

    @Test
    void emailsWaitForTheCommitAndNeverGoOutOnARollback() {
        User bob = user("u2", "bob@x.test");
        when(users.findById("u2")).thenReturn(Optional.of(bob));

        TransactionSynchronizationManager.initSynchronization();
        emailSubscriber.onSignal(mentioned("USER", "u1", List.of("u2"), List.of()));
        verify(email, never()).sendMentionEmail(any(), any(), any(), any(), any());
        rollback();
        verify(email, never()).sendMentionEmail(any(), any(), any(), any(), any());

        TransactionSynchronizationManager.initSynchronization();
        emailSubscriber.onSignal(mentioned("USER", "u1", List.of("u2"), List.of()));
        commit();
        verify(email).sendMentionEmail(eq(bob), any(), any(), any(), any());
    }

    @Test
    void anAgentAuthoredMentionStillEmailsThePerson() {
        User bob = user("u2", "bob@x.test");
        when(users.findById("u2")).thenReturn(Optional.of(bob));

        emailSubscriber.onSignal(mentioned("AGENT", null, List.of("u2"), List.of()));

        verify(email).sendMentionEmail(eq(bob), any(), any(), any(), any());
    }

    @Test
    void oneFailedEmailDoesNotStopTheRest() {
        User bob = user("u2", "bob@x.test");
        User cy = user("u3", "cy@x.test");
        when(users.findById("u2")).thenReturn(Optional.of(bob));
        when(users.findById("u3")).thenReturn(Optional.of(cy));
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(email)
                .sendMentionEmail(eq(bob), any(), any(), any(), any());

        emailSubscriber.onSignal(mentioned("USER", "u1", List.of("u2", "u3"), List.of()));

        verify(email).sendMentionEmail(eq(cy), any(), any(), any(), any());
    }

    // ---- EmailService itself ----

    @Test
    void theMentionEmailIsShortEscapedAndLinksToTheThread() {
        List<String[]> sent = new ArrayList<>();
        EmailService service = new EmailService() {
            @Override
            protected void deliver(String toEmail, String subject, String html) {
                sent.add(new String[]{toEmail, subject, html});
            }
        };

        service.sendMentionEmail(user("u2", "bob@x.test"), "Ann <b>", "PRD / spec.md", "see <script>x</script>",
                "http://x/app/projects/p1/engineering/issues/ENG-1?doc=d1&comment=c1");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)[0]).isEqualTo("bob@x.test");
        assertThat(sent.get(0)[1]).isEqualTo("Ann <b> mentioned you on PRD / spec.md");
        assertThat(sent.get(0)[2]).contains("&lt;script&gt;").doesNotContain("<script>")
                .contains("href=\"http://x/app/projects/p1/engineering/issues/ENG-1?doc=d1&amp;comment=c1\"");
    }

    @Test
    void aMemberWithoutAnEmailAddressIsSkipped() {
        List<String> sent = new ArrayList<>();
        EmailService service = new EmailService() {
            @Override
            protected void deliver(String toEmail, String subject, String html) {
                sent.add(toEmail);
            }
        };

        service.sendMentionEmail(user("u2", null), "Ann", "Doc", "x", "http://x");
        service.sendMentionEmail(null, "Ann", "Doc", "x", "http://x");

        assertThat(sent).isEmpty();
    }

    private static User user(String id, String emailAddress) {
        User u = new User();
        u.setId(id);
        u.setEmail(emailAddress);
        u.setName("User " + id);
        return u;
    }
}
