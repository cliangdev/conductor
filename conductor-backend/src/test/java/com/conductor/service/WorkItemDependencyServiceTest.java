package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemDependency;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkItemDependencyRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkItemDependencyServiceTest {

    private static final String PROJECT = "proj-1";
    private static final ProjectActor ACTOR = ProjectActor.agent("test-agent");

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemDependencyRepository dependencyRepository;
    @Mock private WorkItemWorkflowService workflowService;

    private WorkItemDependencyService service;
    private Project project;
    private Project otherProject;

    @BeforeEach
    void setUp() {
        service = new WorkItemDependencyService(workItemRepository, dependencyRepository, workflowService);
        project = new Project();
        project.setId(PROJECT);
        project.setKey("COND");
        otherProject = new Project();
        otherProject.setId("proj-2");
        otherProject.setKey("OTH");
        put("a", 1, project);
        put("b", 2, project);
        put("x", 3, otherProject);
        when(dependencyRepository.save(any(WorkItemDependency.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void put(String id, int sequence, Project p) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(p);
        w.setSequenceNumber(sequence);
        when(workItemRepository.findById(id)).thenReturn(Optional.of(w));
    }

    @Test
    void addingALinkStoresItWithTheActorAndKind() {
        WorkItemDependency link = service.add(PROJECT, "a", "BLOCKS", "b", ACTOR);

        assertThat(link.getWorkItemId()).isEqualTo("a");
        assertThat(link.getRelatedWorkItemId()).isEqualTo("b");
        assertThat(link.getKind()).isEqualTo("BLOCKS");
        assertThat(link.getProjectId()).isEqualTo(PROJECT);
        assertThat(link.getCreatedByLabel()).isEqualTo("test-agent");
    }

    @Test
    void anItemCannotDependOnItself() {
        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "a", ACTOR))
                .isInstanceOf(BusinessException.class).hasMessageContaining("itself");
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void anItemFromAnotherProjectIsRefused() {
        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "x", ACTOR))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not found in this project");
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void anUnknownRelatedItemIsRefused() {
        when(workItemRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "ghost", ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void theSubjectItemMustBeInTheProject() {
        assertThatThrownBy(() -> service.add(PROJECT, "x", "BLOCKS", "b", ACTOR))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void anUnknownKindIsRefused() {
        assertThatThrownBy(() -> service.add(PROJECT, "a", "RELATES", "b", ACTOR))
                .isInstanceOf(BusinessException.class).hasMessageContaining("kind");
    }

    @Test
    void aBlocksCycleIsAConflictNamingBothItems() {
        // b already reaches a through other items, so a BLOCKS b would close a loop.
        when(dependencyRepository.isReachableViaBlocks("b", "a")).thenReturn(true);

        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "b", ACTOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COND-1").hasMessageContaining("COND-2").hasMessageContaining("cycle");
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void theOutgoingBlocksCapIsAConflict() {
        when(dependencyRepository.countByWorkItemIdAndKind("a", "BLOCKS"))
                .thenReturn((long) WorkItemDependencyService.MAX_BLOCKS_PER_ITEM);

        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "b", ACTOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("25");
    }

    @Test
    void theIncomingBlocksCapIsAConflict() {
        when(dependencyRepository.countByRelatedWorkItemIdAndKind("b", "BLOCKS"))
                .thenReturn((long) WorkItemDependencyService.MAX_BLOCKS_PER_ITEM);

        assertThatThrownBy(() -> service.add(PROJECT, "a", "BLOCKS", "b", ACTOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("25");
    }

    @Test
    void aDuplicateLinkIsReturnedNotStoredAgain() {
        WorkItemDependency existing = new WorkItemDependency();
        existing.setId("dep-1");
        when(dependencyRepository.findByWorkItemIdAndRelatedWorkItemIdAndKind("a", "b", "BLOCKS"))
                .thenReturn(Optional.of(existing));

        assertThat(service.add(PROJECT, "a", "BLOCKS", "b", ACTOR)).isSameAs(existing);
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void discoveredFromSkipsTheCycleAndCapChecks() {
        when(dependencyRepository.isReachableViaBlocks(anyString(), anyString())).thenReturn(true);
        when(dependencyRepository.countByWorkItemIdAndKind(anyString(), anyString())).thenReturn(1000L);

        WorkItemDependency link = service.add(PROJECT, "a", "DISCOVERED_FROM", "b", ACTOR);

        assertThat(link.getKind()).isEqualTo("DISCOVERED_FROM");
    }

    @Test
    void removingALinkThatIsNotOnTheItemIs404() {
        WorkItemDependency other = new WorkItemDependency();
        other.setId("dep-9");
        other.setProjectId(PROJECT);
        other.setWorkItemId("b");
        other.setRelatedWorkItemId("x");
        when(dependencyRepository.findById("dep-9")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.remove(PROJECT, "a", "dep-9"))
                .isInstanceOf(EntityNotFoundException.class);
        verify(dependencyRepository, never()).delete(any());
    }

    @Test
    void removingEitherEndOfALinkWorks() {
        WorkItemDependency link = new WorkItemDependency();
        link.setId("dep-1");
        link.setProjectId(PROJECT);
        link.setWorkItemId("a");
        link.setRelatedWorkItemId("b");
        when(dependencyRepository.findById("dep-1")).thenReturn(Optional.of(link));

        service.remove(PROJECT, "b", "dep-1");

        verify(dependencyRepository).delete(link);
    }
}
