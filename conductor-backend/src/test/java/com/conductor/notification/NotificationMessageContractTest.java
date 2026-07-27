package com.conductor.notification;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the construction and mutability contract of {@link NotificationMessage}, and freezes the exact
 * set of {@link EventType} enum names.
 */
class NotificationMessageContractTest {

    private static final String PROJECT_ID = "proj-1";

    @Test
    void ofThrowsOnNullMetadataValue() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("workItemId", null);

        assertThatThrownBy(() -> NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, metadata))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofThrowsOnNullMetadataKey() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(null, "value");

        assertThatThrownBy(() -> NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, metadata))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofCopiesDefensively() {
        Map<String, String> source = new HashMap<>();
        source.put("workItemId", "wi-1");

        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, source);
        source.put("workItemId", "mutated-after-the-fact");
        source.put("extraKey", "should-not-appear");

        assertThat(event.getMetadata())
                .containsExactlyEntriesOf(Map.of("workItemId", "wi-1"));
    }

    @Test
    void metadataIsUnmodifiable() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));

        assertThatThrownBy(() -> event.getMetadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equalsAndHashCodeIgnoreTimestampButCompareTheRest() {
        NotificationMessage first = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));
        // A second, independently-constructed instance -- Instant.now() may or may not differ by the
        // clock's resolution, which is exactly the point: equality must not depend on it either way.
        NotificationMessage second = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));

        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void equalsDistinguishesByEventTypeProjectIdOrMetadata() {
        NotificationMessage base = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));

        assertThat(base).isNotEqualTo(
                NotificationMessage.of(EventType.COMMENT_ADDED, PROJECT_ID, Map.of("workItemId", "wi-1")));
        assertThat(base).isNotEqualTo(
                NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, "other-project", Map.of("workItemId", "wi-1")));
        assertThat(base).isNotEqualTo(
                NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, Map.of("workItemId", "wi-2")));
    }

    /**
     * These ten strings are persisted, not just in-process constants: they live in the
     * {@code notification_channel_config.event_type} and {@code notification_group_config_event.event_type}
     * columns, and are hardcoded in {@code conductor-frontend/src/hooks/useNotifications.ts}. Renaming or
     * removing one is therefore a DB migration (precedent: {@code V81__rename_issue_event_vocabulary.sql}),
     * not a safe in-place refactor -- this test exists to make that cost visible before it's paid by accident.
     */
    @Test
    void eventTypeNamesAreFrozen() {
        assertThat(EventType.values()).extracting(EventType::name).containsExactlyInAnyOrder(
                "WORK_ITEM_STATUS_CHANGED",
                "REVIEWER_ASSIGNED",
                "REVIEW_SUBMITTED",
                "COMMENT_ADDED",
                "COMMENT_REPLY",
                "MEMBER_JOINED",
                "MEMBER_ROLE_CHANGED",
                "ASSET_ADDED",
                "WORKFLOW_AUTO_PAUSED",
                "GITHUB_PULL_REQUEST"
        );
    }
}
