package com.conductor.v2;

import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemReviewer;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query count of the hot read endpoints must be constant: it may not grow with the number of work
 * items, comments, members or projects. Reads the per-request count from the {@code Server-Timing} header
 * ({@code RequestMetricsFilter}), so it measures exactly what production logs. Each endpoint is warmed up
 * once (caches, first-use lazy init) and then measured with 1 and with 20 of whatever it lists.
 */
class QueryCountApiTest extends AbstractE2ETest {

    private static final Pattern QUERIES = Pattern.compile("db;dur=[\\d.]+;desc=\"(\\d+) queries\"");
    private static final int MANY = 20;

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository memberRepository;
    @Autowired WorkItemRepository workItemRepository;
    @Autowired DocumentRepository documentRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired CommentReplyRepository replyRepository;
    @Autowired WorkItemReviewerRepository reviewerRepository;

    private HttpHeaders auth;
    private String projectId;
    private User caller;
    private final Map<String, Integer> small = new LinkedHashMap<>();
    private final Map<String, Integer> large = new LinkedHashMap<>();
    private final java.util.List<String> itemIds = new java.util.ArrayList<>();
    private Document document;
    private int seededItems;

    @Test
    void readEndpointsIssueAConstantNumberOfQueries() {
        login();
        projectId = createProject("QC Project " + UUID.randomUUID());
        caller = userRepository.findAll().stream()
                .filter(u -> "qc-queries@example.com".equals(u.getEmail())).findFirst().orElseThrow();

        seed(1);
        measureInto(small);
        seed(MANY - 1);
        measureInto(large);

        System.out.println("QUERY-COUNTS (N=1 -> N=" + MANY + "): "
                + small.keySet().stream().map(k -> k + " " + small.get(k) + "->" + large.get(k)).toList());
        for (String endpoint : small.keySet()) {
            assertThat(large.get(endpoint))
                    .as("queries for %s with %d vs %d rows", endpoint, MANY, 1)
                    .isEqualTo(small.get(endpoint));
        }
    }

    // ── measuring ────────────────────────────────────────────────────────────────────────────────

    private void measureInto(Map<String, Integer> into) {
        String base = "/api/v2/projects/" + projectId + "/work-items";
        String firstItem = itemIds.get(0);
        String displayId = workItemRepository.findById(firstItem).orElseThrow().getSequenceNumber() + "";
        String key = projectRepository.findById(projectId).orElseThrow().getKey();
        measure(into, "work-item list", base);
        measure(into, "work-item get", base + "/" + firstItem);
        measure(into, "work-item by-display", base + "/by-display/" + key + "-" + displayId);
        measure(into, "comments list", base + "/" + firstItem + "/comments");
        measure(into, "available-transitions", base + "/" + firstItem + "/available-transitions");
        measure(into, "members", "/api/v1/projects/" + projectId + "/members");
        measure(into, "projects list", "/api/v1/projects");
        measure(into, "sidebar workflows", "/api/v1/projects/" + projectId
                + "/workflows?lifecycle=true&state=PUBLISHED&sidebar=true");
    }

    private void measure(Map<String, Integer> into, String name, String path) {
        get(path); // warm up: statechart/user caches, first-use initialisation
        var resp = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertThat(resp.getStatusCode()).as(name + " status: " + resp.getBody()).isEqualTo(HttpStatus.OK);
        String timing = resp.getHeaders().getFirst("Server-Timing");
        assertThat(timing).as(name + " Server-Timing").isNotNull();
        Matcher m = QUERIES.matcher(timing);
        assertThat(m.find()).as(timing).isTrue();
        into.put(name, Integer.parseInt(m.group(1)));
    }

    private void get(String path) {
        rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(auth), String.class);
    }

    // ── seeding ──────────────────────────────────────────────────────────────────────────────────

    private void seed(int count) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        for (int i = 0; i < count; i++) {
            seededItems++;
            var created = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("title", "Item " + seededItems, "type", "PRD",
                            "workflow", "ENGINEERING", "tags", java.util.List.of("t" + seededItems, "common")),
                            auth), Map.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String id = (String) created.getBody().get("id");
            itemIds.add(id);
            WorkItem item = workItemRepository.findById(id).orElseThrow();

            User member = newUser();
            ProjectMember pm = new ProjectMember();
            pm.setProject(project);
            pm.setUser(member);
            pm.setRole(MemberRole.REVIEWER);
            memberRepository.save(pm);

            WorkItemReviewer reviewer = new WorkItemReviewer();
            reviewer.setWorkItemId(id);
            reviewer.setUserId(member.getId());
            reviewer.setAssignedBy(caller.getId());
            reviewerRepository.save(reviewer);

            // comments (each with a reply) all on the first item, which is the one the comments list reads
            WorkItem first = workItemRepository.findById(itemIds.get(0)).orElseThrow();
            if (document == null) {
                document = new Document();
                document.setId(UUID.randomUUID().toString());
                document.setWorkItem(first);
                document.setFilename("spec.md");
                document.setContentType("text/markdown");
                document.setContent("line one\nline two\n");
                document.setStoragePath("qc/" + UUID.randomUUID());
                documentRepository.save(document);
            }
            Comment comment = new Comment();
            comment.setWorkItem(first);
            comment.setDocument(document);
            comment.setAuthor(member);
            comment.setContent("comment " + seededItems);
            comment.setLineNumber(1);
            comment.setQuotedText("line one");
            commentRepository.save(comment);
            CommentReply reply = new CommentReply();
            reply.setComment(comment);
            reply.setAuthor(caller);
            reply.setContent("reply " + seededItems);
            replyRepository.save(reply);

            // more projects for the caller, so the projects list has N rows too
            createProject("QC Extra " + UUID.randomUUID());
        }
    }

    private User newUser() {
        User u = new User();
        u.setId(UUID.randomUUID().toString());
        u.setFirebaseUid("qc:" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("QC User");
        return userRepository.save(u);
    }

    private void login() {
        var resp = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "qc-queries@example.com", "password", "conductor"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        auth = new HttpHeaders();
        auth.setBearerAuth((String) resp.getBody().get("accessToken"));
        auth.setContentType(MediaType.APPLICATION_JSON);
    }

    private String createProject(String name) {
        var resp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "qc"), auth), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }
}
