package com.conductor.marketing.growth;

import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/** Seeds users, projects, members, App Store Connect connections and Work Items for the growth signal tests. */
final class GrowthSignalsFixture {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ConnectionRepository connections;
    private final WorkItemRepository workItems;

    GrowthSignalsFixture(UserRepository users, ProjectRepository projects, ProjectMemberRepository members,
                         ConnectionRepository connections, WorkItemRepository workItems) {
        this.users = users;
        this.projects = projects;
        this.members = members;
        this.connections = connections;
        this.workItems = workItems;
    }

    User user() {
        User u = new User();
        u.setFirebaseUid("growth-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Growth Tester");
        return users.save(u);
    }

    Project project(User owner) {
        Project p = new Project();
        p.setName("Growth");
        p.setKey("GR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
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

    /** An active App Store Connect connection whose config carries the given app id (and country when not null). */
    void appStoreConnect(Project project, String appId, String ratingsCountry) {
        String config = ratingsCountry == null ? "{\"appId\":\"" + appId + "\"}"
                : "{\"appId\":\"" + appId + "\",\"ratingsCountry\":\"" + ratingsCountry + "\"}";
        connect(project, "app-store-connect", config);
    }

    String connect(Project project, String connectorId, String configJson) {
        Connection c = new Connection();
        c.setProjectId(project.getId());
        c.setConnectorId(connectorId);
        c.setDisplayLabel(connectorId + " " + UUID.randomUUID());
        c.setAuthType("API_KEY");
        c.setStatus("ACTIVE");
        c.setConfigJson(configJson);
        c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connections.saveAndFlush(c).getId();
    }

    private int sequence = 0;

    WorkItem workItem(Project project, User creator, String workflow) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("LISTING");
        item.setTitle("Change " + UUID.randomUUID());
        item.setCreatedBy(creator);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCurrentStatus("LIVE");
        item.setSequenceNumber(++sequence);
        return workItems.saveAndFlush(item);
    }

    /** A random 10-digit App Store id, unique enough across tests that share one database. */
    static String appId() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
