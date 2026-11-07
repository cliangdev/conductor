package com.conductor.marketing.aivisibility;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;

import java.util.UUID;

/** Seeds users, projects and members for the AI-visibility tests. */
final class AiVisibilityFixture {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    AiVisibilityFixture(UserRepository users, ProjectRepository projects, ProjectMemberRepository members) {
        this.users = users;
        this.projects = projects;
        this.members = members;
    }

    User user() {
        User u = new User();
        u.setFirebaseUid("aivis-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Visibility Tester");
        return users.save(u);
    }

    Project project(User owner, String name) {
        Project p = new Project();
        p.setName(name);
        p.setKey("AV" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        return projects.save(p);
    }

    void member(Project project, User user, MemberRole role) {
        ProjectMember m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(role);
        members.save(m);
    }
}
