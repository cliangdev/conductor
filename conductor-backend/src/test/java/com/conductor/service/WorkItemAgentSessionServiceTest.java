package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemStepRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Input checks on the agent session upsert: every bound is a 400 here, never a 500 from the column. */
class WorkItemAgentSessionServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ITEM_ID = "wi-1";

    private WorkItemAgentSessionRepository sessionRepository;
    private WorkItemAgentSessionService service;

    @BeforeEach
    void setUp() {
        WorkItemRepository workItemRepository = mock(WorkItemRepository.class);
        sessionRepository = mock(WorkItemAgentSessionRepository.class);
        service = new WorkItemAgentSessionService(workItemRepository, sessionRepository,
                mock(WorkItemStepRunRepository.class), Clock.systemUTC());
        WorkItem item = new WorkItem();
        item.setId(ITEM_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        item.setProject(project);
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
        when(sessionRepository.findByWorkItemIdAndSessionKey(any(), any())).thenReturn(Optional.empty());
        when(sessionRepository.save(any(WorkItemAgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void anAgentLabelOverTheColumnWidthIsABadRequest() {
        assertThatThrownBy(() -> service.upsert(PROJECT_ID, ITEM_ID, "run-1", "a".repeat(129), null, "WORKING",
                null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("agentLabel");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void anAgentLabelOfExactlyTheColumnWidthIsAccepted() {
        WorkItemAgentSession saved = service.upsert(PROJECT_ID, ITEM_ID, "run-1", "a".repeat(128), null,
                "WORKING", null, null);

        assertThat(saved.getAgentLabel()).hasSize(128);
    }

    @Test
    void aBlankAgentLabelIsStillABadRequest() {
        assertThatThrownBy(() -> service.upsert(PROJECT_ID, ITEM_ID, "run-1", "  ", null, "WORKING", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aSessionKeyOverTheColumnWidthIsABadRequest() {
        assertThatThrownBy(() -> service.upsert(PROJECT_ID, ITEM_ID, "k".repeat(129), "claude", null, "WORKING",
                null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sessionKey");
    }
}
