package com.conductor.notification;

import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The mention and project-doc comment events, from the signal the comment code publishes to the chat card. */
class DiscordCommentMentionTest {

    static final String PROJECT = "p1";
    static final String LINK = "http://localhost:3000/app/projects/p1/engineering/issues/ENG-7?doc=d1&comment=c1";

    DiscordProvider discord = new DiscordProvider(mock(RestTemplate.class), mock(RestTemplate.class),
            "http://localhost:3000");
    NotificationSignalMapper mapper = new NotificationSignalMapper();

    private Signal mentionSignal() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("projectId", PROJECT);
        payload.put("commentId", "c1");
        payload.put("authorName", "Ann");
        payload.put("commentAuthor", "Ann");
        payload.put("authorKind", "USER");
        payload.put("documentTitle", "Launch PRD / spec.md");
        payload.put("excerpt", "@Bob please check");
        payload.put("link", LINK);
        payload.put("mentionedNames", "@Bob, @Editor Bot");
        payload.put("userIds", List.of("u-bob"));
        payload.put("agentIds", List.of("a-1"));
        return Signal.of(SignalTypes.CONDUCTOR_COMMENT_MENTIONED, PROJECT, "c1", Instant.now(), payload,
                new SignalOrigin("comment", "c1"));
    }

    @Test
    void theMentionSignalIsDeliverableAndMapsToTheMentionEvent() {
        assertThat(mapper.isDeliverable(SignalTypes.CONDUCTOR_COMMENT_MENTIONED)).isTrue();

        NotificationMessage event = mapper.toNotificationEvent(mentionSignal());

        assertThat(event.getEventType()).isEqualTo(EventType.COMMENT_MENTIONED);
        // The id lists are not chat text and do not survive the flat envelope; the names do.
        assertThat(event.getMetadata()).containsEntry("mentionedNames", "@Bob, @Editor Bot")
                .doesNotContainKeys("userIds", "agentIds");
    }

    @Test
    void theCardSaysWhoMentionedWhomWhereAndLinksToTheThread() {
        String card = discord.format(mapper.toNotificationEvent(mentionSignal()));

        assertThat(card).contains("Mentioned in a comment")
                .contains("Ann mentioned @Bob, @Editor Bot on Launch PRD / spec.md")
                .contains("@Bob please check")
                .contains("\"url\":\"" + LINK + "\"");
    }

    @Test
    void mentionsAreRoutedToTheIssuesChannelGroup() {
        assertThat(ChannelGroup.forEventType(EventType.COMMENT_MENTIONED)).contains(ChannelGroup.ISSUES);
        assertThat(ChannelGroup.forEventType(EventType.DOC_COMMENT_ADDED)).contains(ChannelGroup.ISSUES);
        assertThat(ChannelGroup.forEventType(EventType.DOC_COMMENT_REPLY)).contains(ChannelGroup.ISSUES);
    }

    @Test
    void projectDocCommentsRenderWithTheDocTitleAndItsLink() {
        Signal added = Signal.of(SignalTypes.CONDUCTOR_PROJECT_DOC_COMMENT_ADDED, PROJECT, "pd-1", Instant.now(), Map.of(
                "docId", "pd-1", "docTitle", "Q3 Roadmap", "commentAuthor", "Ann", "excerpt", "cut the last goal",
                "link", "http://localhost:3000/app/projects/p1/docs/pd-1?comment=c9"), new SignalOrigin("comment", "pd-1"));
        Signal replied = Signal.of(SignalTypes.CONDUCTOR_PROJECT_DOC_COMMENT_REPLIED, PROJECT, "pd-1", Instant.now(), Map.of(
                "docId", "pd-1", "docTitle", "Q3 Roadmap", "commentAuthor", "Bob", "excerpt", "done",
                "link", "http://localhost:3000/app/projects/p1/docs/pd-1?comment=c9"), new SignalOrigin("comment", "pd-1"));

        String addedCard = discord.format(mapper.toNotificationEvent(added));
        String repliedCard = discord.format(mapper.toNotificationEvent(replied));

        assertThat(addedCard).contains("Comment Added").contains("Ann commented on: Q3 Roadmap").contains("cut the last goal")
                .contains("/docs/pd-1?comment=c9");
        assertThat(repliedCard).contains("Comment Reply").contains("Bob replied on: Q3 Roadmap");
    }

    @Test
    void aMentionNeverPingsAnyoneInChat() {
        NotificationMessage event = NotificationMessage.of(EventType.COMMENT_MENTIONED, PROJECT, Map.of(
                "commentAuthor", "@everyone", "mentionedNames", "@here", "documentTitle", "Doc"));

        String card = discord.format(event);
        assertThat(card).startsWith("{\"embeds\":[{").contains("@everyone mentioned @here on Doc")
                .endsWith("\"allowed_mentions\":{\"parse\":[]}}");
    }
}
