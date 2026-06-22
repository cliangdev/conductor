package com.conductor.integration.connector.github;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.Project;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookVerification;
import com.conductor.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubConnectorTest {

    private static final String SECRET = "webhook-secret-123";
    private static final String PROJECT_ID = "proj-1";

    @Mock private IssueRepository issueRepository;
    private GitHubConnector connector;

    @BeforeEach
    void setUp() {
        connector = new GitHubConnector(issueRepository, new ObjectMapper());
    }

    private ConnectionContext ctx() {
        return new ConnectionContext(PROJECT_ID, "github", "conn-1", null, null, null, Map.of(), SECRET);
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void verify_acceptsCorrectSignature_rejectsWrong() throws Exception {
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

        HttpHeaders good = new HttpHeaders();
        good.add("X-Hub-Signature-256", sign(body));
        assertThat(connector.verify(body, good, ctx()).valid()).isTrue();

        HttpHeaders bad = new HttpHeaders();
        bad.add("X-Hub-Signature-256", "sha256=deadbeef");
        assertThat(connector.verify(body, bad, ctx()).valid()).isFalse();

        WebhookVerification missing = connector.verify(body, new HttpHeaders(), ctx());
        assertThat(missing.valid()).isFalse();
    }

    @Test
    void handleEvent_mergedPr_transitionsIssueToDone() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        Issue issue = new Issue();
        issue.setProject(project);
        issue.setStatus(IssueStatus.IN_PROGRESS);
        when(issueRepository.findByProjectKeyAndSequenceNumber("PROJ", 1)).thenReturn(Optional.of(issue));

        String payload = "{\"action\":\"closed\",\"pull_request\":{\"merged\":true,"
                + "\"body\":\"closes conductor/PROJ-1\",\"html_url\":\"https://github.com/x/y/pull/3\"}}";
        InboundEvent event = new InboundEvent("delivery-1", "pull_request", payload, Map.of());

        connector.handleEvent(event, ctx());

        assertThat(issue.getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(issue.getGithubPrUrl()).isEqualTo("https://github.com/x/y/pull/3");
        verify(issueRepository).save(issue);
    }

    @Test
    void handleEvent_nonPullRequestEvent_isIgnored() {
        InboundEvent event = new InboundEvent("delivery-2", "push", "{}", Map.of());
        connector.handleEvent(event, ctx());
        verify(issueRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
