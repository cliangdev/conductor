package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.repository.ProjectMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class ProjectSecurityService {

    private final ProjectMemberRepository projectMemberRepository;

    public ProjectSecurityService(ProjectMemberRepository projectMemberRepository) {
        this.projectMemberRepository = projectMemberRepository;
    }

    public boolean isProjectMember(String projectId, String userId) {
        return projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
    }

    public boolean isProjectAdmin(String projectId, String userId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole() == MemberRole.ADMIN)
                .orElse(false);
    }
}
