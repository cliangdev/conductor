package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work Item hierarchy against real Postgres and the real seeded Workflows: a PRD with a nested epic and two
 * TASK children, finished one by one. Covers what the unit tests mock out -- the TASK Workflow seeded for a
 * new project, the default top-level-only list, the recursive tree, cycle timestamps stamped on write, the
 * {@code children_done} roll-up completing the epic (and only the epic), milestone inheritance, and the
 * cascade guard on delete.
 */
class WorkItemHierarchyE2ETest extends AbstractE2ETest {

    private HttpHeaders auth;
    private String projectId;

    @BeforeEach
    void loginAndCreateProject() {
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "e2e-hierarchy@example.com", "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        auth = new HttpHeaders();
        auth.setBearerAuth((String) login.getBody().get("accessToken"));
        auth.setContentType(MediaType.APPLICATION_JSON);

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Hierarchy E2E", "description", "test"), auth), Map.class);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) project.getBody().get("id");
    }

    private String items() {
        return url("/api/v2/projects/" + projectId + "/work-items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(HttpMethod method, String url, Object body, HttpStatus expected) {
        ResponseEntity<Map> response = rest.exchange(url, method, new HttpEntity<>(body, auth), Map.class);
        assertThat(response.getStatusCode()).as("%s %s", method, url).isEqualTo(expected);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query) {
        ResponseEntity<List> response = rest.exchange(items() + query, HttpMethod.GET, new HttpEntity<>(auth), List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String create(Map<String, Object> body) {
        return (String) call(HttpMethod.POST, items(), body, HttpStatus.CREATED).get("id");
    }

    private Map<String, Object> get(String id) {
        return call(HttpMethod.GET, items() + "/" + id, null, HttpStatus.OK);
    }

    private Map<String, Object> move(String id, String status) {
        return call(HttpMethod.PATCH, items() + "/" + id, Map.of("status", status), HttpStatus.OK);
    }

    @Test
    void aPrdWithANestedEpicAndTwoTasksRollsUpAndStampsCycleTimes() {
        var milestone = call(HttpMethod.POST, url("/api/v2/projects/" + projectId + "/milestones"),
                Map.of("name", "Beta", "targetOn", "2026-12-01"), HttpStatus.CREATED);
        String milestoneId = (String) milestone.get("id");

        String prd = create(Map.of("title", "Big feature", "type", "PRD", "workflow", "ENGINEERING",
                "priority", "P1", "appetite", "2w", "milestoneId", milestoneId));
        // A child names no Workflow or type: it lands on TASK/TASK and inherits the PRD's milestone.
        String epic = create(Map.of("title", "Epic", "parentWorkItemId", prd));
        String t1 = create(Map.of("title", "Task one", "parentWorkItemId", epic));
        String t2 = create(Map.of("title", "Task two", "parentWorkItemId", epic));

        var prdBody = get(prd);
        assertThat(prdBody.get("priority")).isEqualTo("P1");
        assertThat(prdBody.get("appetite")).isEqualTo("2 weeks");
        assertThat(prdBody.get("appetiteDays")).isEqualTo(14);
        assertThat(prdBody.get("milestoneName")).isEqualTo("Beta");
        assertThat(prdBody.get("childCount")).isEqualTo(1);

        var epicBody = get(epic);
        assertThat(epicBody.get("workflow")).isEqualTo("TASK");
        assertThat(epicBody.get("type")).isEqualTo("TASK");
        assertThat(epicBody.get("status")).isEqualTo("TODO");
        assertThat(epicBody.get("parentWorkItemId")).isEqualTo(prd);
        assertThat(epicBody.get("milestoneId")).isEqualTo(milestoneId);
        assertThat(epicBody.get("childCount")).isEqualTo(2);

        // The default list is top-level only; parentId=any and rootId widen it.
        assertThat(list("")).extracting(m -> m.get("id")).containsExactly(prd);
        assertThat(list("?parentId=any")).extracting(m -> m.get("id")).containsExactlyInAnyOrder(prd, epic, t1, t2);
        assertThat(list("?rootId=" + prd)).extracting(m -> m.get("id")).containsExactlyInAnyOrder(epic, t1, t2);
        assertThat(list("?parentId=" + epic)).extracting(m -> m.get("id")).containsExactlyInAnyOrder(t1, t2);
        assertThat(list("?priority=P1")).extracting(m -> m.get("id")).containsExactly(prd);
        assertThat(list("?withProgress=true").get(0).get("progress")).isNotNull();

        // The tree is one nested read.
        var tree = call(HttpMethod.GET, items() + "/" + prd + "/tree", null, HttpStatus.OK);
        assertThat(tree.get("id")).isEqualTo(prd);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> epics = (List<Map<String, Object>>) tree.get("children");
        assertThat(epics).extracting(m -> m.get("id")).containsExactly(epic);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> leaves = (List<Map<String, Object>>) epics.get(0).get("children");
        assertThat(leaves).extracting(m -> m.get("id")).containsExactly(t1, t2);

        // Starting a task stamps startedAt; finishing it stamps completedAt.
        var started = move(t1, "IN_PROGRESS");
        assertThat(started.get("startedAt")).isNotNull();
        assertThat(started.get("completedAt")).isNull();
        var finished = move(t1, "DONE");
        assertThat(finished.get("completedAt")).isNotNull();
        // One task done is not enough: the epic stays open.
        assertThat(get(epic).get("status")).isEqualTo("TODO");

        move(t2, "IN_PROGRESS");
        move(t2, "DONE");

        // The last child finishing completes the epic -- stamped like any other move -- but not the PRD.
        var epicDone = get(epic);
        assertThat(epicDone.get("status")).isEqualTo("DONE");
        assertThat(epicDone.get("completedAt")).isNotNull();
        var prdAfter = get(prd);
        assertThat(prdAfter.get("status")).isEqualTo("DRAFT");
        assertThat(prdAfter.get("completedAt")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) prdAfter.get("progress");
        assertThat(progress).containsEntry("total", 2).containsEntry("done", 2).containsEntry("percent", 100)
                .containsEntry("criteriaTotal", 0).containsEntry("criteriaMet", 0);

        // Reopening a task stamps completedAt away again and never reopens the epic.
        var reopened = move(t2, "TODO");
        assertThat(reopened.get("completedAt")).isNull();
        assertThat(reopened.get("startedAt")).isNotNull();
        assertThat(get(epic).get("status")).isEqualTo("DONE");

        // A milestone knows how far its items have got.
        var milestones = rest.exchange(url("/api/v2/projects/" + projectId + "/milestones"), HttpMethod.GET,
                new HttpEntity<>(auth), List.class).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> beta = (Map<String, Object>) milestones.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> betaProgress = (Map<String, Object>) beta.get("progress");
        assertThat(betaProgress).containsEntry("topLevelTotal", 1).containsEntry("topLevelDone", 0)
                .containsEntry("leafTotal", 2).containsEntry("leafDone", 1);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callList(HttpMethod method, String url, Object body) {
        ResponseEntity<List> response = rest.exchange(url, method, new HttpEntity<>(body, auth), List.class);
        assertThat(response.getStatusCode()).as("%s %s", method, url).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @Test
    void dependenciesCriteriaAndAgentSessionsDriveTheSignalsAndTheRollup() {
        String prd = create(Map.of("title", "Tracked feature", "type", "PRD", "workflow", "ENGINEERING"));
        String a = create(Map.of("title", "Task A", "parentWorkItemId", prd));
        // B is blocked by A from the moment it is created.
        String b = create(Map.of("title", "Task B", "parentWorkItemId", prd, "blockedBy", List.of(a)));

        var bBody = get(b);
        assertThat(map(bBody, "signals")).containsEntry("blocked", true).containsEntry("ready", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockedBy = (List<Map<String, Object>>) map(bBody, "signals").get("blockedBy");
        assertThat(blockedBy).extracting(m -> m.get("id")).containsExactly(a);

        // The ready queue under the PRD is just A.
        assertThat(list("?rootId=" + prd + "&ready=true")).extracting(m -> m.get("id")).containsExactly(a);
        assertThat(list("?rootId=" + prd + "&blocked=true")).extracting(m -> m.get("id")).containsExactly(b);

        // Dependencies list both ends, and a reverse BLOCKS is a cycle.
        var deps = call(HttpMethod.GET, items() + "/" + b + "/dependencies", null, HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockers = (List<Map<String, Object>>) deps.get("blockedBy");
        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0)).containsEntry("workItemId", a);
        assertThat(blockers.get(0).get("dependencyId")).isNotNull();
        call(HttpMethod.POST, items() + "/" + a + "/dependencies",
                Map.of("kind", "BLOCKS", "relatedWorkItemId", b), HttpStatus.OK); // idempotent re-assert
        call(HttpMethod.POST, items() + "/" + b + "/dependencies",
                Map.of("kind", "BLOCKS", "relatedWorkItemId", a), HttpStatus.CONFLICT);
        call(HttpMethod.POST, items() + "/" + a + "/dependencies",
                Map.of("kind", "BLOCKS", "relatedWorkItemId", a), HttpStatus.BAD_REQUEST);
        var graph = call(HttpMethod.GET, items() + "/" + prd + "/dependency-graph", null, HttpStatus.OK);
        assertThat((List<?>) graph.get("nodes")).hasSize(3);
        assertThat((List<?>) graph.get("edges")).hasSize(1);

        // Finishing A unblocks B: the ready queue is now B, and A (terminal) is not ready.
        move(a, "IN_PROGRESS");
        move(a, "DONE");
        assertThat(list("?rootId=" + prd + "&ready=true")).extracting(m -> m.get("id")).containsExactly(b);
        assertThat(map(get(b), "signals")).containsEntry("blocked", false).containsEntry("ready", true);

        // Two criteria on B, one met: the patch reports 1/2, the tree node and the PRD's progress agree.
        var criteria = callList(HttpMethod.PUT, items() + "/" + b + "/criteria", List.of(
                Map.of("text", "It works", "kind", "manual", "externalRef", "AC-P0-1.1"),
                Map.of("text", "It is fast")));
        assertThat(criteria).extracting(m -> m.get("position")).containsExactly(0, 1);
        assertThat(criteria).extracting(m -> m.get("kind")).containsExactly("manual", "auto");
        String firstCriterion = (String) criteria.get(0).get("criterionId");
        var patched = call(HttpMethod.PATCH, items() + "/" + b + "/criteria/" + firstCriterion,
                Map.of("met", true, "evidence", "checked by hand"), HttpStatus.OK);
        assertThat(patched).containsEntry("criteriaMet", 1).containsEntry("criteriaTotal", 2);
        assertThat(map(patched, "criterion")).containsEntry("met", true).containsEntry("evidence", "checked by hand");
        assertThat(map(patched, "criterion").get("metAt")).isNotNull();

        var tree = call(HttpMethod.GET, items() + "/" + prd + "/tree", null, HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) tree.get("children");
        Map<String, Object> bNode = nodes.stream().filter(n -> b.equals(n.get("id"))).findFirst().orElseThrow();
        assertThat(bNode).containsEntry("criteriaTotal", 2).containsEntry("criteriaMet", 1);
        assertThat(map(bNode, "signals")).containsEntry("ready", true);
        assertThat(map(get(prd), "progress")).containsEntry("criteriaTotal", 2).containsEntry("criteriaMet", 1)
                .containsEntry("total", 2).containsEntry("done", 1);

        // An agent asks a question on B: the PRD shows needsInput, B shows the activity.
        String sessions = items() + "/" + b + "/agent-sessions/run-1";
        var asking = call(HttpMethod.PUT, sessions, Map.of("agentLabel", "claude-code",
                "activity", "NEEDS_INPUT", "note", "Which database?"), HttpStatus.OK);
        assertThat(asking).containsEntry("activity", "NEEDS_INPUT").containsEntry("mode", "DELEGATE");
        assertThat(map(get(prd), "signals")).containsEntry("needsInput", true);
        assertThat(map(get(b), "agentActivity")).containsEntry("activity", "NEEDS_INPUT")
                .containsEntry("note", "Which database?");
        // Re-reporting is an idempotent upsert, not a second session.
        call(HttpMethod.PUT, sessions, Map.of("agentLabel", "claude-code", "activity", "NEEDS_INPUT",
                "note", "Which database?"), HttpStatus.OK);
        assertThat(callList(HttpMethod.GET, items() + "/" + b + "/agent-sessions", null)).hasSize(1);

        // Done closes the session: the PRD stops asking and B reports no live activity.
        var done = call(HttpMethod.PUT, sessions, Map.of("agentLabel", "claude-code", "activity", "DONE"),
                HttpStatus.OK);
        assertThat(done.get("endedAt")).isNotNull();
        assertThat(map(get(prd), "signals")).containsEntry("needsInput", false);
        assertThat(get(b).get("agentActivity")).isNull();
    }

    @Test
    void reparentingIsGuardedAndDeletingAParentNeedsCascade() {
        String prd = create(Map.of("title", "Parent", "type", "PRD", "workflow", "ENGINEERING"));
        String child = create(Map.of("title", "Child", "parentWorkItemId", prd));
        String grandchild = create(Map.of("title", "Grandchild", "parentWorkItemId", child));

        // Making the PRD a child of its own grandchild would be a cycle.
        call(HttpMethod.PATCH, items() + "/" + prd, Map.of("parentWorkItemId", grandchild), HttpStatus.CONFLICT);
        call(HttpMethod.PATCH, items() + "/" + prd, Map.of("parentWorkItemId", prd), HttpStatus.BAD_REQUEST);
        call(HttpMethod.PATCH, items() + "/" + child, Map.of("priority", "P9"), HttpStatus.BAD_REQUEST);
        call(HttpMethod.PATCH, items() + "/" + child, Map.of("appetite", "soon"), HttpStatus.BAD_REQUEST);

        // Detaching with "" makes it top-level.
        call(HttpMethod.PATCH, items() + "/" + grandchild, Map.of("parentWorkItemId", ""), HttpStatus.OK);
        assertThat(get(grandchild).get("parentWorkItemId")).isNull();
        call(HttpMethod.PATCH, items() + "/" + grandchild, Map.of("parentWorkItemId", child), HttpStatus.OK);

        // A parent with sub-items is not deleted unless asked to take them with it.
        call(HttpMethod.DELETE, items() + "/" + prd, null, HttpStatus.CONFLICT);
        assertThat(get(grandchild).get("id")).isEqualTo(grandchild);

        ResponseEntity<Void> deleted = rest.exchange(items() + "/" + prd + "?cascade=true", HttpMethod.DELETE,
                new HttpEntity<>(auth), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        for (String gone : List.of(prd, child, grandchild)) {
            ResponseEntity<Map> missing = rest.exchange(items() + "/" + gone, HttpMethod.GET,
                    new HttpEntity<>(auth), Map.class);
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
