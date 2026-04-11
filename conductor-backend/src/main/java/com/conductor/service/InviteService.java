package com.conductor.service;

import com.conductor.entity.Invite;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.InviteExpiredException;
import com.conductor.generated.model.AcceptInviteResponse;
import com.conductor.generated.model.InviteResponse;
import com.conductor.repository.InviteRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    private final InviteRepository inviteRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectSecurityService projectSecurityService;
    private final EmailService emailService;

    public InviteService(
            InviteRepository inviteRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository,
            ProjectSecurityService projectSecurityService,
            EmailService emailService) {
        this.inviteRepository = inviteRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
        this.projectSecurityService = projectSecurityService;
        this.emailService = emailService;
    }

    @Transactional
    public InviteResponse createInvite(String projectId, String email, String roleStr, User inviter) {
        if (!projectSecurityService.isProjectAdmin(projectId, inviter.getId())) {
            throw new AccessDeniedException("Only project admins can send invites");
        }

        MemberRole role = parseInviteRole(roleStr);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        userRepository.findByEmail(email).ifPresent(invitee -> {
            if (projectMemberRepository.existsByProjectIdAndUserId(projectId, invitee.getId())) {
                throw new ConflictException("User is already a project member");
            }
        });

        inviteRepository.findByProjectIdAndEmailAndStatus(projectId, email, "PENDING").ifPresent(existing -> {
            throw new ConflictException("Invite already pending for this email");
        });

        Invite invite = new Invite();
        invite.setProject(project);
        invite.setEmail(email);
        invite.setRole(role);
        invite.setToken(UUID.randomUUID().toString());
        invite.setInvitedBy(inviter);
        invite.setExpiresAt(OffsetDateTime.now().plusHours(72));
        inviteRepository.save(invite);

        try {
            emailService.sendInviteEmail(email, inviter.getName(), project.getName(), invite.getToken());
        } catch (Exception e) {
            log.warn("Failed to send invite email to {}: {}", email, e.getMessage());
        }

        return toInviteResponse(invite);
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> listPendingInvites(String projectId, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can list invites");
        }

        return inviteRepository.findByProjectIdAndStatus(projectId, "PENDING").stream()
                .map(this::toInviteResponse)
                .toList();
    }

    @Transactional
    public void cancelInvite(String projectId, String inviteId, User caller) {
        if (!projectSecurityService.isProjectAdmin(projectId, caller.getId())) {
            throw new AccessDeniedException("Only project admins can cancel invites");
        }

        Invite invite = inviteRepository.findById(inviteId)
                .orElseThrow(() -> new EntityNotFoundException("Invite not found"));

        invite.setStatus("CANCELLED");
        inviteRepository.save(invite);
    }

    @Transactional
    public AcceptInviteResponse acceptInvite(String token, User currentUser) {
        Invite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Invite not found"));

        if (!"PENDING".equals(invite.getStatus())) {
            throw new ConflictException("Invite already used");
        }

        if (invite.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InviteExpiredException("Invite has expired");
        }

        Project project = invite.getProject();

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(currentUser);
        member.setRole(invite.getRole());
        projectMemberRepository.save(member);

        invite.setStatus("ACCEPTED");
        inviteRepository.save(invite);

        return new AcceptInviteResponse(project.getId(), project.getName(), invite.getRole().name());
    }

    private MemberRole parseInviteRole(String roleStr) {
        try {
            MemberRole role = MemberRole.valueOf(roleStr.toUpperCase());
            if (role == MemberRole.ADMIN) {
                throw new BusinessException("Cannot invite with ADMIN role");
            }
            return role;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid role: " + roleStr);
        }
    }

    private InviteResponse toInviteResponse(Invite invite) {
        return new InviteResponse(
                invite.getId(),
                invite.getEmail(),
                invite.getRole().name(),
                invite.getExpiresAt())
                .token(invite.getToken())
                .status(invite.getStatus());
    }
}
