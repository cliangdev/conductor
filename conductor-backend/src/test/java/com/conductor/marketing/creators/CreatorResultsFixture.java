package com.conductor.marketing.creators;

import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the users, projects, Work Items, destinations, daily metric rows and (when the table exists on this
 * branch) tracked links a creator results test reads. Everything is created inside the test's transaction.
 */
final class CreatorResultsFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ConnectionRepository connections;
    private final WorkItemRepository workItems;
    private final PostPublishTargetRepository targets;
    private final PostPublishTargetMetricRepository metrics;
    private final ChannelMetricDailyRepository daily;
    private final EntityManager entityManager;

    private int sequence = 0;

    CreatorResultsFixture(UserRepository users, ProjectRepository projects, ProjectMemberRepository members,
                          ConnectionRepository connections, WorkItemRepository workItems,
                          PostPublishTargetRepository targets, PostPublishTargetMetricRepository metrics,
                          ChannelMetricDailyRepository daily, EntityManager entityManager) {
        this.users = users;
        this.projects = projects;
        this.members = members;
        this.connections = connections;
        this.workItems = workItems;
        this.targets = targets;
        this.metrics = metrics;
        this.daily = daily;
        this.entityManager = entityManager;
    }

    User user() {
        User u = new User();
        u.setFirebaseUid("creators-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Creators Tester");
        return users.save(u);
    }

    Project project(User owner) {
        Project p = new Project();
        p.setName("Creators");
        p.setKey("CR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
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

    /** A CREATORS Work Item; {@code fields} is key/value pairs (handle, offerCode, linkToken ...). */
    WorkItem creator(Project project, User by, String status, Map<String, String> fields) {
        WorkItem item = item(project, by, "CREATOR", "CREATORS", status, "Creator " + UUID.randomUUID());
        ObjectNode node = MAPPER.createObjectNode();
        fields.forEach(node::put);
        item.setFields(node);
        return workItems.saveAndFlush(item);
    }

    /** A Post Work Item (type POST on the MARKETING workflow), optionally a child of {@code parent}. */
    WorkItem post(Project project, User by, String title, String parentId) {
        WorkItem item = item(project, by, "POST", "MARKETING", "PUBLISHED", title);
        item.setParentWorkItemId(parentId);
        return workItems.saveAndFlush(item);
    }

    WorkItem tagged(WorkItem item, String tag) {
        item.getTags().add(tag);
        return workItems.saveAndFlush(item);
    }

    /** Any other Work Item (a TASK child, an ENGINEERING issue ...). */
    WorkItem item(Project project, User by, String type, String workflow, String status, String title) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType(type);
        item.setTitle(title);
        item.setCreatedBy(by);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        item.setSequenceNumber(++sequence);
        return workItems.saveAndFlush(item);
    }

    PostPublishTarget destination(WorkItem post, String platform, PostPublishTargetState state) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(post);
        target.setPlatform(platform);
        // a destination that has not gone out yet needs a connection unless it is on the manual lane
        target.setLane(state == PostPublishTargetState.PUBLISHED ? PublishLane.NATIVE : PublishLane.MANUAL);
        target.setState(state);
        target.setFireTime(OffsetDateTime.now().minusDays(3));
        target.setIdempotencyKey("creators-test:" + UUID.randomUUID());
        return targets.saveAndFlush(target);
    }

    void snapshot(PostPublishTarget target, Project project, OffsetDateTime observedAt, Long views, boolean unavailable) {
        PostPublishTargetMetric metric = new PostPublishTargetMetric();
        metric.setTargetId(target.getId());
        metric.setWorkItemId(target.getWorkItem().getId());
        metric.setProjectId(project.getId());
        metric.setPlatform(target.getPlatform());
        metric.setPeriodKey(observedAt.toString());
        metric.setObservedAt(observedAt);
        metric.setViews(views);
        metric.setUnavailable(unavailable);
        metrics.saveAndFlush(metric);
    }

    String connection(Project project, String connectorId) {
        Connection c = new Connection();
        c.setProjectId(project.getId());
        c.setConnectorId(connectorId);
        c.setDisplayLabel(connectorId + " " + UUID.randomUUID());
        c.setAuthType("API_KEY");
        c.setStatus("ACTIVE");
        c.setConfigJson("{}");
        c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connections.saveAndFlush(c).getId();
    }

    void dailyRow(Project project, String connectionId, String connectorId, String channel, String metric,
                  String dimension, LocalDate day, double value, boolean provisional) {
        daily.upsert(UUID.randomUUID().toString(), project.getId(), connectionId, connectorId, channel, metric,
                dimension, day, BigDecimal.valueOf(value), provisional);
    }

    /** Inserts one {@code tracked_link} row (V189). */
    void trackedLink(Project project, User by, String kind, String token, String workItemId) {
        entityManager.createNativeQuery("""
                INSERT INTO tracked_link (id, project_id, kind, family, token, url, work_item_id, label, created_by, created_at)
                VALUES (:id, :projectId, :kind, :family, :token, :url, :workItemId, :label, :createdBy, now())
                """)
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("projectId", project.getId())
                .setParameter("kind", kind)
                .setParameter("family", "creators")
                .setParameter("token", token)
                .setParameter("url", "https://apps.apple.com/app/apple-store/id1?pt=1&ct=" + token)
                .setParameter("workItemId", workItemId)
                .setParameter("label", "link " + token)
                .setParameter("createdBy", by.getId())
                .executeUpdate();
    }
}
