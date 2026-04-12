package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.IssuesApi;
import com.conductor.generated.model.CreateIssueRequest;
import com.conductor.generated.model.IssueResponse;
import com.conductor.generated.model.IssueStatus;
import com.conductor.generated.model.IssueType;
import com.conductor.generated.model.PatchIssueRequest;
import com.conductor.service.IssueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class IssueController implements IssuesApi {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @Override
    public ResponseEntity<IssueResponse> createIssue(String projectId, CreateIssueRequest createIssueRequest) {
        User caller = currentUser();
        IssueResponse response = issueService.createIssue(projectId, createIssueRequest, caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<IssueResponse>> listIssues(String projectId, IssueType type, IssueStatus status) {
        User caller = currentUser();
        List<IssueResponse> issues = issueService.listIssues(projectId, type, status, caller);
        return ResponseEntity.ok(issues);
    }

    @Override
    public ResponseEntity<IssueResponse> getIssue(String projectId, String issueId) {
        User caller = currentUser();
        IssueResponse response = issueService.getIssue(projectId, issueId, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<IssueResponse> patchIssue(String projectId, String issueId, PatchIssueRequest patchIssueRequest) {
        User caller = currentUser();
        IssueResponse response = issueService.patchIssue(projectId, issueId, patchIssueRequest, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteIssue(String projectId, String issueId) {
        issueService.deleteIssue(projectId, issueId);
        return ResponseEntity.noContent().build();
    }

    private static final Logger log = LoggerFactory.getLogger(IssueController.class);

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            log.warn("currentUser() expected User principal but got {} (auth type={})",
                    principal == null ? "null" : principal.getClass().getName(),
                    auth == null ? "null" : auth.getClass().getSimpleName());
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return (User) principal;
    }
}
