package com.conductor.controller;

import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.generated.api.OrgsApi;
import com.conductor.generated.model.CreateOrgRequest;
import com.conductor.generated.model.OrgResponse;
import com.conductor.service.OrgService;
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
public class OrgController implements OrgsApi {

    private static final Logger log = LoggerFactory.getLogger(OrgController.class);

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @Override
    public ResponseEntity<OrgResponse> createOrg(CreateOrgRequest createOrgRequest) {
        User caller = currentUser();
        Organization org = orgService.createOrg(caller.getId(), createOrgRequest.getName(), createOrgRequest.getSlug());
        return ResponseEntity.status(201).body(toResponse(org));
    }

    @Override
    public ResponseEntity<OrgResponse> getOrg(String orgId) {
        User caller = currentUser();
        Organization org = orgService.getOrg(orgId, caller.getId());
        return ResponseEntity.ok(toResponse(org));
    }

    @Override
    public ResponseEntity<List<OrgResponse>> listMyOrgs() {
        User caller = currentUser();
        List<OrgResponse> responses = orgService.getOrgsForUser(caller.getId()).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    private OrgResponse toResponse(Organization org) {
        return new OrgResponse(org.getId(), org.getName(), org.getSlug(), org.getCreatedAt());
    }

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
