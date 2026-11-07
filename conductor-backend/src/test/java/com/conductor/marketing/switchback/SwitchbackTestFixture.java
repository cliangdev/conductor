package com.conductor.marketing.switchback;

import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.WorkflowSeeder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds users, projects, App Store Connect and Apple Ads connections and their daily rows for the switchback tests.
 * Besides the two series a test settles on it writes the rows a careless query would pick up by mistake (the all-sources
 * store total, a keyword's own Apple Ads rows), so a test that passes proves the right rows were read.
 */
final class SwitchbackTestFixture {

    /** A Monday. Four weeks from here end on Sunday 2026-11-01. */
    static final LocalDate FIRST_MONDAY = LocalDate.of(2026, 10, 5);

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ConnectionRepository connections;
    private final ChannelMetricDailyRepository daily;
    private final WorkflowSeeder workflowSeeder;
    private final TransactionTemplate transactions;

    SwitchbackTestFixture(UserRepository users, ProjectRepository projects, ProjectMemberRepository members,
                          ConnectionRepository connections, ChannelMetricDailyRepository daily,
                          WorkflowSeeder workflowSeeder, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.projects = projects;
        this.members = members;
        this.connections = connections;
        this.daily = daily;
        this.workflowSeeder = workflowSeeder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A project's connections, the two series are written through. */
    record Feeds(String projectId, String storeConnectionId, String adsConnectionId) {}

    User user() {
        User u = new User();
        u.setFirebaseUid("switchback-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Switchback Tester");
        return users.save(u);
    }

    Project project(User owner) {
        Project p = new Project();
        p.setName("Rexipe");
        p.setKey("SB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
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

    void withChangesWorkflow(Project project) {
        workflowSeeder.seedMarketingChanges(project);
    }

    Feeds feeds(String projectId) {
        return new Feeds(projectId, connection(projectId, "app-store-connect", "App Store Connect"),
                connection(projectId, "apple-search-ads", "Apple Ads account"));
    }

    private String connection(String projectId, String connectorId, String label) {
        Connection c = new Connection();
        c.setProjectId(projectId);
        c.setConnectorId(connectorId);
        c.setDisplayLabel(label);
        c.setAuthType("OAUTH2");
        c.setStatus("ACTIVE");
        c.setConfigJson("{}");
        c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connections.saveAndFlush(c).getId();
    }

    /** Seven days of search and Apple Ads first-time downloads, one value per day, settled. */
    void week(Feeds feeds, LocalDate monday, double searchPerDay, double adsPerDay) {
        for (int i = 0; i < 7; i++) {
            day(feeds, monday.plusDays(i), searchPerDay, adsPerDay, false);
        }
    }

    /** Four weeks on, off, on, off from {@link #FIRST_MONDAY}: 42 / 21 search and 21 / 0 paid a week, n = 126. */
    void fourWeeks(Feeds feeds) {
        week(feeds, FIRST_MONDAY, 6, 3);
        week(feeds, FIRST_MONDAY.plusWeeks(1), 3, 0);
        week(feeds, FIRST_MONDAY.plusWeeks(2), 6, 3);
        week(feeds, FIRST_MONDAY.plusWeeks(3), 3, 0);
    }

    void day(Feeds feeds, LocalDate day, double search, double ads, boolean provisional) {
        searchOnly(feeds, day, search, provisional);
        adsOnly(feeds, day, ads, provisional);
    }

    void searchOnly(Feeds feeds, LocalDate day, double search, boolean provisional) {
        put(feeds.projectId(), feeds.storeConnectionId(), "app-store-connect", "STORE", "downloads",
                SwitchbackDataReader.SEARCH_SOURCE, day, search, provisional);
        // not the series: the all-sources total
        put(feeds.projectId(), feeds.storeConnectionId(), "app-store-connect", "STORE", "downloads", "", day,
                search + 100, provisional);
    }

    void adsOnly(Feeds feeds, LocalDate day, double ads, boolean provisional) {
        put(feeds.projectId(), feeds.adsConnectionId(), "apple-search-ads", "ADS", "new_downloads", "", day, ads,
                provisional);
        // not the series: the keyword's own rows and another metric of the total
        put(feeds.projectId(), feeds.adsConnectionId(), "apple-search-ads", "ADS", "new_downloads",
                "keyword:555:recipe app", day, 99, provisional);
        put(feeds.projectId(), feeds.adsConnectionId(), "apple-search-ads", "ADS", "installs", "", day, ads + 50,
                provisional);
    }

    /** A day total point of another Apple Ads metric, with no first-time downloads beside it. */
    void adsMetricOnly(Feeds feeds, String metric, LocalDate day, double value) {
        put(feeds.projectId(), feeds.adsConnectionId(), "apple-search-ads", "ADS", metric, "", day, value, false);
    }

    private void put(String projectId, String connectionId, String connectorId, String channel, String metric,
                     String dimension, LocalDate day, double value, boolean provisional) {
        transactions.executeWithoutResult(status -> daily.upsert(UUID.randomUUID().toString(), projectId, connectionId,
                connectorId, channel, metric, dimension, day, BigDecimal.valueOf(value), provisional));
    }

    /** Four weeks on, off, on, off from {@link #FIRST_MONDAY}. */
    static List<SwitchbackWeek> fourAlternatingWeeks() {
        List<SwitchbackWeek> weeks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            weeks.add(new SwitchbackWeek(FIRST_MONDAY.plusWeeks(i), i % 2 == 0));
        }
        return weeks;
    }
}
