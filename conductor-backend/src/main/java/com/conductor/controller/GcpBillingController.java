package com.conductor.controller;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.generated.model.BqDatasetsResponse;
import com.conductor.generated.model.BqDatasetsResponseDatasetsInner;
import com.conductor.generated.model.GcpProjectsResponse;
import com.conductor.generated.model.GcpProjectsResponseProjectsInner;
import com.conductor.entity.Connection;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.connector.GcpBillingConnector;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("!local")
@RequestMapping("/api/v1")
public class GcpBillingController {

    private final GcpBillingConnector gcpBillingConnector;
    private final ConnectionService connectionService;
    private final OAuthFlowService oAuthFlowService;
    private final ProjectMemberRepository projectMemberRepository;

    public GcpBillingController(GcpBillingConnector gcpBillingConnector,
                                 ConnectionService connectionService,
                                 OAuthFlowService oAuthFlowService,
                                 ProjectMemberRepository projectMemberRepository) {
        this.gcpBillingConnector = gcpBillingConnector;
        this.connectionService = connectionService;
        this.oAuthFlowService = oAuthFlowService;
        this.projectMemberRepository = projectMemberRepository;
    }

    @GetMapping("/projects/{projectId}/integrations/gcp-billing/gcp-projects")
    public ResponseEntity<GcpProjectsResponse> listGcpProjects(@PathVariable String projectId) {
        requireMember(projectId);
        String accessToken = requireGcpAccessToken(projectId, "gcp-billing");
        List<Map<String, String>> projects = gcpBillingConnector.listGcpProjects(accessToken);
        GcpProjectsResponse response = new GcpProjectsResponse();
        projects.forEach(p -> response.addProjectsItem(
                new GcpProjectsResponseProjectsInner()
                        .projectId(p.get("projectId")).name(p.get("name"))));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/projects/{projectId}/integrations/gcp-billing/bq-datasets")
    public ResponseEntity<BqDatasetsResponse> listBqDatasets(
            @PathVariable String projectId,
            @RequestParam String gcpProjectId) {
        requireMember(projectId);
        if (gcpProjectId == null || !gcpProjectId.matches("[a-z0-9A-Z:_\\-]+")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid gcpProjectId format");
        }
        String accessToken = requireGcpAccessToken(projectId, "gcp-billing");
        List<Map<String, String>> datasets = gcpBillingConnector.listBqDatasets(accessToken, gcpProjectId);
        BqDatasetsResponse response = new BqDatasetsResponse();
        datasets.forEach(d -> response.addDatasetsItem(
                new BqDatasetsResponseDatasetsInner()
                        .datasetId(d.get("datasetId")).location(d.get("location"))));
        return ResponseEntity.ok(response);
    }

    private String requireGcpAccessToken(String projectId, String connectorId) {
        Connection conn = connectionService.findSingle(projectId, connectorId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.CONFLICT, "No OAuth credentials stored — complete OAuth first"));
        DecryptedCredentials creds = connectionService.decrypt(conn);
        if (creds.accessToken() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "No OAuth credentials stored — complete OAuth first");
        }
        if (creds.expiresAt() != null &&
                creds.expiresAt().isBefore(java.time.Instant.now().plusSeconds(60))) {
            return oAuthFlowService.refreshAccessToken(conn, creds.refreshToken());
        }
        return creds.accessToken();
    }

    private void requireMember(String projectId) {
        member(projectId);
    }

    private ProjectMember member(String projectId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser().getId())
                .orElseThrow(() -> new AccessDeniedException("Not a member of this project"));
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
