package com.conductor.repository;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the V112 ranked-FTS upgrade of {@code searchByProjectIdAndQuery} -- title-weighted-A vs
 * content-weighted-C ranking, the {@code limit} clause, and blank-query safety (an empty {@code
 * tsquery} matches nothing rather than erroring -- see the repository method's javadoc for why that's
 * an accepted behavior change from the old LIKE query's "matches everything").
 */
@Transactional
class ProjectDocRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private ProjectDocRepository projectDocRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        project = new Project();
        project.setName("Test Project");
        project.setKey("PDR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectRepository.save(project);
    }

    private ProjectDoc newDoc(String title, String content) {
        ProjectDoc doc = new ProjectDoc();
        doc.setProject(project);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setCreatedByLabel("test-actor");
        doc.setUpdatedByLabel("test-actor");
        return projectDocRepository.saveAndFlush(doc);
    }

    @Test
    void titleHitOutranksBodyOnlyHit() {
        newDoc("General Notes", "Here's a note about the gizmo component and how it's wired.");
        newDoc("Gizmo Overview", "This document explains general project setup steps unrelated to hardware.");

        List<ProjectDoc> results = projectDocRepository.searchByProjectIdAndQuery(project.getId(), "gizmo", 10);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTitle()).isEqualTo("Gizmo Overview");
        assertThat(results.get(1).getTitle()).isEqualTo("General Notes");
    }

    @Test
    void limitCapsResultCount() {
        newDoc("Gizmo One", "gizmo");
        newDoc("Gizmo Two", "gizmo");
        newDoc("Gizmo Three", "gizmo");

        List<ProjectDoc> results = projectDocRepository.searchByProjectIdAndQuery(project.getId(), "gizmo", 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void blankQueryReturnsEmptyResultsWithoutError() {
        newDoc("Gizmo Overview", "gizmo");

        assertThatCode(() -> projectDocRepository.searchByProjectIdAndQuery(project.getId(), "", 10))
                .doesNotThrowAnyException();
        assertThat(projectDocRepository.searchByProjectIdAndQuery(project.getId(), "", 10)).isEmpty();
    }

    @Test
    void noMatchesReturnsEmptyList() {
        newDoc("Gizmo Overview", "gizmo");

        List<ProjectDoc> results = projectDocRepository.searchByProjectIdAndQuery(project.getId(), "nonexistentword", 10);

        assertThat(results).isEmpty();
    }

    @Test
    void scopedToProject() {
        newDoc("Gizmo Overview", "gizmo");
        Project otherProject = new Project();
        otherProject.setName("Other Project");
        otherProject.setKey("OTH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        otherProject.setCreatedBy(project.getCreatedBy());
        projectRepository.save(otherProject);

        List<ProjectDoc> results = projectDocRepository.searchByProjectIdAndQuery(otherProject.getId(), "gizmo", 10);

        assertThat(results).isEmpty();
    }
}
