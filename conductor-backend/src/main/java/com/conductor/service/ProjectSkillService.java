package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectSkill;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.model.RegisterSkillRequest;
import com.conductor.generated.model.SkillDto;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.ProjectSkillRepository;
import com.conductor.workflow.lifecycle.SkillRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Project-scoped skill registry (#240 §3). Lets a user/authoring agent register bindable Claude Code skill
 * ids for their project so a non-engineering Workflow can bind a custom skill from a transition step and
 * Publish without a backend redeploy — the {@link SkillRegistry} then accepts it. Mirrors the fat-service
 * template: owns {@code @Transactional}, membership/role checks, entity→DTO assembly.
 */
@Service
public class ProjectSkillService {

    private final ProjectSkillRepository projectSkillRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final SkillRegistry skillRegistry;

    public ProjectSkillService(ProjectSkillRepository projectSkillRepository,
                               ProjectRepository projectRepository,
                               ProjectSecurityService projectSecurityService,
                               SkillRegistry skillRegistry) {
        this.projectSkillRepository = projectSkillRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.skillRegistry = skillRegistry;
    }

    /** Built-in skills (shipped, every project) plus the project's registered skills. */
    @Transactional(readOnly = true)
    public List<SkillDto> listSkills(String projectId, String callerId) {
        verifyMembership(projectId, callerId);
        List<SkillDto> result = new ArrayList<>();
        for (SkillRegistry.BuiltInSkill s : skillRegistry.builtInSkills()) {
            SkillDto dto = new SkillDto(s.id(), true);
            dto.setLabel(s.label());
            dto.setDescription(s.description());
            result.add(dto);
        }
        for (ProjectSkill ps : projectSkillRepository.findAllByProjectId(projectId)) {
            SkillDto dto = new SkillDto(ps.getSkillId(), false);
            dto.setLabel(ps.getLabel());
            dto.setDescription(ps.getDescription());
            result.add(dto);
        }
        return result;
    }

    /** The outcome of {@link #registerSkill}: the skill plus whether a new row was created (201) vs updated (200). */
    public record SkillRegistration(SkillDto skill, boolean created) {
    }

    /** Register (or update) a project-scoped bindable skill. Idempotent on the skill id. */
    @Transactional
    public SkillRegistration registerSkill(String projectId, RegisterSkillRequest request, String callerId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, callerId)) {
            throw new ForbiddenException("Only ADMIN or CREATOR can register skills");
        }
        String skillId = request.getId() == null ? null : request.getId().trim();
        if (skillId == null || skillId.isEmpty()) {
            throw new UnprocessableEntityException("Skill id is required");
        }
        if (skillRegistry.isBuiltIn(skillId)) {
            throw new UnprocessableEntityException("'" + skillId + "' is a built-in skill and is already bindable");
        }

        Optional<ProjectSkill> existing = projectSkillRepository.findByProjectIdAndSkillId(projectId, skillId);
        boolean created = existing.isEmpty();
        ProjectSkill skill = existing.orElseGet(() -> {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new EntityNotFoundException("Project not found"));
            ProjectSkill fresh = new ProjectSkill();
            fresh.setProject(project);
            fresh.setSkillId(skillId);
            return fresh;
        });
        skill.setLabel(request.getLabel());
        skill.setDescription(request.getDescription());
        ProjectSkill saved = projectSkillRepository.save(skill);

        SkillDto dto = new SkillDto(saved.getSkillId(), false);
        dto.setLabel(saved.getLabel());
        dto.setDescription(saved.getDescription());
        return new SkillRegistration(dto, created);
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
    }
}
