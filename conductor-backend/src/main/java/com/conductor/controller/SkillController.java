package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.SkillsApi;
import com.conductor.generated.model.RegisterSkillRequest;
import com.conductor.generated.model.SkillDto;
import com.conductor.service.ProjectSkillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Project-scoped skill registry endpoints (#240 §3) — discover and register the bindable Claude Code skill
 * ids a Workflow may use from a {@code skill} transition step. Thin controller; logic in
 * {@link ProjectSkillService}.
 */
@RestController
public class SkillController implements SkillsApi {

    private final ProjectSkillService projectSkillService;

    public SkillController(ProjectSkillService projectSkillService) {
        this.projectSkillService = projectSkillService;
    }

    @Override
    public ResponseEntity<List<SkillDto>> listSkills(String projectId) {
        return ResponseEntity.ok(projectSkillService.listSkills(projectId, currentUserId()));
    }

    @Override
    public ResponseEntity<SkillDto> registerSkill(String projectId, RegisterSkillRequest registerSkillRequest) {
        SkillDto dto = projectSkillService.registerSkill(projectId, registerSkillRequest, currentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            throw new ClassCastException("Expected User principal but got: "
                    + (principal == null ? "null" : principal.getClass().getName()));
        }
        return ((User) principal).getId();
    }
}
