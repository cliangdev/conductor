package com.conductor.entity;

import com.conductor.notification.RollupLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rolling Discord message of one notification channel ({@link NotificationGroupConfig}): which
 * message is being edited, when it was started and last touched, and a line per Work Item. See
 * {@code NotificationRollupService} for the rules; this row is only ever read and rewritten when an
 * event arrives -- there is no timer behind it.
 */
@Entity
@Table(name = "notification_rollup")
public class NotificationRollup {

    @Id
    @Column(name = "config_id", length = 36, nullable = false, updatable = false)
    private String configId;

    @Column(name = "message_id", length = 64, nullable = false)
    private String messageId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", columnDefinition = "jsonb", nullable = false)
    private Map<String, RollupLine> lines = new LinkedHashMap<>();

    public String getConfigId() { return configId; }
    public void setConfigId(String configId) { this.configId = configId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, RollupLine> getLines() { return lines; }
    public void setLines(Map<String, RollupLine> lines) { this.lines = lines; }
}
