package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Liveness of one agent run on a Work Item, keyed by {@code (workItemId, sessionKey)} and upserted as the
 * agent reports. {@code mode} is {@code DELEGATE} (a person stays the assignee and the agent acts for them)
 * or {@code ASSIGNEE}; {@code activity} is {@code WORKING}, {@code NEEDS_INPUT} (the question is in
 * {@code note}), {@code DONE} or {@code ERRORED}. A session with {@code endedAt} set is closed.
 *
 * <p>Separate from {@link WorkItemStepRun}, which records what a transition's Step produced for a Review
 * gate; this is only "is something working on it, and does it need me".
 */
@Entity
@Table(name = "work_item_agent_sessions")
public class WorkItemAgentSession {

    public static final String MODE_DELEGATE = "DELEGATE";
    public static final String MODE_ASSIGNEE = "ASSIGNEE";

    public static final String ACTIVITY_WORKING = "WORKING";
    public static final String ACTIVITY_NEEDS_INPUT = "NEEDS_INPUT";
    public static final String ACTIVITY_DONE = "DONE";
    public static final String ACTIVITY_ERRORED = "ERRORED";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    @Column(name = "session_key", length = 128, nullable = false, updatable = false)
    private String sessionKey;

    @Column(name = "agent_label", length = 128, nullable = false)
    private String agentLabel;

    @Column(name = "mode", length = 16, nullable = false)
    private String mode = MODE_DELEGATE;

    @Column(name = "activity", length = 16, nullable = false)
    private String activity;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "step_run_id", length = 36)
    private String stepRunId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private OffsetDateTime lastHeartbeatAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (startedAt == null) {
            startedAt = now;
        }
        if (lastHeartbeatAt == null) {
            lastHeartbeatAt = now;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }

    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }

    public String getAgentLabel() { return agentLabel; }
    public void setAgentLabel(String agentLabel) { this.agentLabel = agentLabel; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getActivity() { return activity; }
    public void setActivity(String activity) { this.activity = activity; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getStepRunId() { return stepRunId; }
    public void setStepRunId(String stepRunId) { this.stepRunId = stepRunId; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime endedAt) { this.endedAt = endedAt; }
}
