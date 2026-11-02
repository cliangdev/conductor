package com.conductor.service;

import com.conductor.entity.WorkItemAgentSession;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemStepRunRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Records that an agent is (or was) working on a Work Item, so the board can show "an agent is on this" and
 * "an agent is waiting for you" without anyone typing it.
 *
 * <p>{@link #upsert} is idempotent per {@code (item, sessionKey)}: the first report creates the session, later
 * ones refresh its activity, note and heartbeat, so a skill can report as often as it likes. {@code DONE}
 * and {@code ERRORED} close the session; {@code WORKING} or {@code NEEDS_INPUT} on a closed one reopens it.
 * This never touches the item's assignee -- a {@code DELEGATE} session leaves the person who delegated as
 * the assignee. Access control is the caller's job.
 */
@Service
public class WorkItemAgentSessionService {

    private static final Set<String> MODES =
            Set.of(WorkItemAgentSession.MODE_DELEGATE, WorkItemAgentSession.MODE_ASSIGNEE);
    private static final Set<String> ACTIVITIES = Set.of(WorkItemAgentSession.ACTIVITY_WORKING,
            WorkItemAgentSession.ACTIVITY_NEEDS_INPUT, WorkItemAgentSession.ACTIVITY_DONE,
            WorkItemAgentSession.ACTIVITY_ERRORED);

    private final WorkItemRepository workItemRepository;
    private final WorkItemAgentSessionRepository sessionRepository;
    private final WorkItemStepRunRepository stepRunRepository;
    private final Clock clock;

    @Autowired
    public WorkItemAgentSessionService(WorkItemRepository workItemRepository,
                                       WorkItemAgentSessionRepository sessionRepository,
                                       WorkItemStepRunRepository stepRunRepository) {
        this(workItemRepository, sessionRepository, stepRunRepository, Clock.systemUTC());
    }

    WorkItemAgentSessionService(WorkItemRepository workItemRepository,
                                WorkItemAgentSessionRepository sessionRepository,
                                WorkItemStepRunRepository stepRunRepository, Clock clock) {
        this.workItemRepository = workItemRepository;
        this.sessionRepository = sessionRepository;
        this.stepRunRepository = stepRunRepository;
        this.clock = clock;
    }

    /** The item's sessions, most recently heard from first. */
    @Transactional(readOnly = true)
    public List<WorkItemAgentSession> list(String projectId, String itemId) {
        requireItem(projectId, itemId);
        return sessionRepository.findByWorkItemIdOrderByLastHeartbeatAtDesc(itemId);
    }

    /**
     * Creates or refreshes the session {@code sessionKey} on the item. {@code mode} defaults to
     * {@code DELEGATE}. {@code note} replaces the previous note (a blank one clears it, so answering a
     * question removes it); {@code stepRunId} is kept when null.
     */
    @Transactional
    public WorkItemAgentSession upsert(String projectId, String itemId, String sessionKey, String agentLabel,
                                       String mode, String activity, String note, String stepRunId) {
        requireItem(projectId, itemId);
        if (sessionKey == null || sessionKey.isBlank() || sessionKey.length() > 128) {
            throw new BusinessException("sessionKey is required and at most 128 characters");
        }
        if (agentLabel == null || agentLabel.isBlank()) {
            throw new BusinessException("agentLabel is required");
        }
        String resolvedMode = mode == null || mode.isBlank()
                ? WorkItemAgentSession.MODE_DELEGATE : mode.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(resolvedMode)) {
            throw new BusinessException("mode must be DELEGATE or ASSIGNEE, got '" + mode + "'");
        }
        String resolvedActivity = activity == null ? "" : activity.trim().toUpperCase(Locale.ROOT);
        if (!ACTIVITIES.contains(resolvedActivity)) {
            throw new BusinessException("activity must be one of WORKING, NEEDS_INPUT, DONE, ERRORED, got '"
                    + activity + "'");
        }
        if (stepRunId != null && !stepRunId.isBlank()) {
            stepRunRepository.findById(stepRunId)
                    .filter(run -> run.getWorkItem() != null && itemId.equals(run.getWorkItem().getId()))
                    .orElseThrow(() -> new BusinessException("stepRunId " + stepRunId
                            + " is not a step run of this Work Item"));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        WorkItemAgentSession session = sessionRepository.findByWorkItemIdAndSessionKey(itemId, sessionKey)
                .orElseGet(() -> {
                    WorkItemAgentSession fresh = new WorkItemAgentSession();
                    fresh.setProjectId(projectId);
                    fresh.setWorkItemId(itemId);
                    fresh.setSessionKey(sessionKey);
                    fresh.setStartedAt(now);
                    return fresh;
                });
        session.setAgentLabel(agentLabel.strip());
        session.setMode(resolvedMode);
        session.setActivity(resolvedActivity);
        session.setNote(note == null || note.isBlank() ? null : note);
        if (stepRunId != null && !stepRunId.isBlank()) {
            session.setStepRunId(stepRunId);
        }
        session.setLastHeartbeatAt(now);
        boolean closing = WorkItemAgentSession.ACTIVITY_DONE.equals(resolvedActivity)
                || WorkItemAgentSession.ACTIVITY_ERRORED.equals(resolvedActivity);
        if (closing) {
            if (session.getEndedAt() == null) {
                session.setEndedAt(now);
            }
        } else {
            session.setEndedAt(null);
        }
        return sessionRepository.save(session);
    }

    private void requireItem(String projectId, String itemId) {
        boolean found = workItemRepository.findById(itemId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .isPresent();
        if (!found) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }
}
