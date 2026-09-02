package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freeform tags on a Work Item: what is stored, and what filtering finds.
 *
 * <p>Against a real database because both halves live in persistence — the element collection that lets
 * one item carry several tags, and the {@code MEMBER OF} filter that finds them again. A tag you cannot
 * filter by is decoration, so the two are tested together.
 */
class WorkItemTagsIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private WorkItemService workItemService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkflowSeeder workflowSeeder;

    private User caller;
    private Project project;

    @BeforeEach
    void setUp() {
        caller = new User();
        caller.setFirebaseUid("tags-uid-" + UUID.randomUUID());
        caller.setEmail(UUID.randomUUID() + "@example.com");
        caller.setName("Tag Author");
        caller = userRepository.save(caller);

        project = new Project();
        project.setName("Tags Project");
        project.setKey("TG" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(caller);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(caller);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        // Types and the initial status are validated against the bound Workflow, so it has to exist.
        workflowSeeder.seedEngineering(project);
    }

    private WorkItem create(String title, List<String> tags) {
        return workItemService.createWorkItem(project.getId(), "PRD", title, null, "ENGINEERING", tags, caller);
    }

    @Test
    void anItemCarriesSeveralTags() {
        // The whole reason this is a table and not the single `tag` column agents and workflows got.
        WorkItem item = create("Autumn launch", List.of("autumn-campaign", "paid"));

        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .containsExactlyInAnyOrder("autumn-campaign", "paid");
    }

    @Test
    void tagsAreLowerCasedAndDeduplicatedOnWrite() {
        // Otherwise "Autumn" and "autumn" are two tags that look identical in a filter list and match
        // different items.
        WorkItem item = create("Autumn launch", List.of("Autumn-Campaign", "  PAID ", "autumn-campaign"));

        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .containsExactlyInAnyOrder("autumn-campaign", "paid");
    }

    @Test
    void blankTagsAreDroppedRatherThanStored() {
        WorkItem item = create("Autumn launch", List.of("paid", "   ", ""));

        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .containsExactly("paid");
    }

    @Test
    void filteringFindsOnlyItemsCarryingTheTag() {
        create("Autumn launch", List.of("autumn-campaign", "paid"));
        create("Evergreen how-to", List.of("evergreen"));
        create("Untagged", List.of());

        assertThat(workItemService.listWorkItemEntities(
                project.getId(), null, null, null, "autumn-campaign", caller))
                .extracting(WorkItem::getTitle)
                .containsExactly("Autumn launch");
    }

    @Test
    void filteringIsCaseInsensitiveBecauseTheStoredFormIsNormalised() {
        create("Autumn launch", List.of("autumn-campaign"));

        assertThat(workItemService.listWorkItemEntities(
                project.getId(), null, null, null, "AUTUMN-Campaign", caller))
                .extracting(WorkItem::getTitle)
                .containsExactly("Autumn launch");
    }

    @Test
    void noTagFilterReturnsEverythingAsBefore() {
        create("Autumn launch", List.of("autumn-campaign"));
        create("Untagged", List.of());

        assertThat(workItemService.listWorkItemEntities(project.getId(), null, null, null, null, caller))
                .hasSize(2);
        // The blank string is "no filter", not "a tag that is blank".
        assertThat(workItemService.listWorkItemEntities(project.getId(), null, null, null, "  ", caller))
                .hasSize(2);
    }

    @Test
    void patchingTagsReplacesTheWholeSet() {
        // Sent whole, so a client always knows what it is saying. Omitting the field leaves them alone.
        WorkItem item = create("Autumn launch", List.of("autumn-campaign", "paid"));

        workItemService.patchWorkItem(project.getId(), item.getId(), null, null, null, null, null, null,
                List.of("evergreen"), caller);
        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .containsExactly("evergreen");

        workItemService.patchWorkItem(project.getId(), item.getId(), "A new title", null, null, null, null,
                null, null, caller);
        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .containsExactly("evergreen");

        workItemService.patchWorkItem(project.getId(), item.getId(), null, null, null, null, null, null,
                List.of(), caller);
        assertThat(workItemService.getWorkItemEntity(project.getId(), item.getId(), caller).getTags())
                .isEmpty();
    }
}
