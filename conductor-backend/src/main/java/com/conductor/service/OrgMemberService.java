package com.conductor.service;

import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class OrgMemberService {

    private static final Logger log = LoggerFactory.getLogger(OrgMemberService.class);

    public record OrgMemberDetails(
            String userId,
            String name,
            String email,
            OrgMember.OrgRole role,
            OffsetDateTime joinedAt
    ) {}

    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public OrgMemberService(
            OrgRepository orgRepository,
            OrgMemberRepository orgMemberRepository,
            UserRepository userRepository,
            EmailService emailService) {
        this.orgRepository = orgRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Transactional(readOnly = true)
    public List<OrgMemberDetails> getMembers(String orgId, String requestingUserId) {
        orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        orgMemberRepository.findByOrgIdAndUserId(orgId, requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        return orgMemberRepository.findByOrgId(orgId).stream()
                .map(m -> new OrgMemberDetails(
                        m.getUser().getId(),
                        m.getUser().getName(),
                        m.getUser().getEmail(),
                        m.getRole(),
                        m.getJoinedAt()
                ))
                .toList();
    }

    @Transactional
    public void inviteMember(String orgId, String inviterUserId, String email, OrgMember.OrgRole role) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        OrgMember inviterMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, inviterUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        if (inviterMembership.getRole() != OrgMember.OrgRole.ADMIN) {
            throw new ForbiddenException("Only org admins can invite members");
        }

        User inviter = userRepository.findById(inviterUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + inviterUserId));

        userRepository.findByEmail(email).ifPresent(existingUser -> {
            orgMemberRepository.findByOrgIdAndUserId(orgId, existingUser.getId()).ifPresent(existing -> {
                throw new BusinessException("User is already a member of this org");
            });
        });

        try {
            emailService.sendOrgInviteEmail(email, inviter.getName(), org.getName());
        } catch (Exception e) {
            log.warn("Failed to send org invite email to {}: {}", email, e.getMessage());
        }
    }

    @Transactional
    public OrgMemberDetails changeMemberRole(String orgId, String adminUserId, String targetUserId, OrgMember.OrgRole newRole) {
        orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        OrgMember adminMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, adminUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        if (adminMembership.getRole() != OrgMember.OrgRole.ADMIN) {
            throw new ForbiddenException("Only org admins can change member roles");
        }

        OrgMember targetMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found in org: " + targetUserId));

        if (targetMembership.getRole() == OrgMember.OrgRole.ADMIN && newRole == OrgMember.OrgRole.MEMBER) {
            long adminCount = orgMemberRepository.countByOrgIdAndRole(orgId, OrgMember.OrgRole.ADMIN);
            if (adminCount <= 1) {
                throw new BusinessException("Cannot demote the last admin of the org");
            }
        }

        targetMembership.setRole(newRole);
        orgMemberRepository.save(targetMembership);

        User user = targetMembership.getUser();
        return new OrgMemberDetails(
                user.getId(),
                user.getName(),
                user.getEmail(),
                newRole,
                targetMembership.getJoinedAt()
        );
    }

    @Transactional
    public void removeMember(String orgId, String callerUserId, String targetUserId) {
        orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        OrgMember callerMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, callerUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        boolean isSelfRemoval = callerUserId.equals(targetUserId);

        if (!isSelfRemoval && callerMembership.getRole() != OrgMember.OrgRole.ADMIN) {
            throw new ForbiddenException("Only org admins can remove other members");
        }

        OrgMember targetMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found in org: " + targetUserId));

        if (targetMembership.getRole() == OrgMember.OrgRole.ADMIN) {
            long adminCount = orgMemberRepository.countByOrgIdAndRole(orgId, OrgMember.OrgRole.ADMIN);
            if (adminCount <= 1) {
                throw new BusinessException("Cannot remove the last admin of the org");
            }
        }

        orgMemberRepository.delete(targetMembership);
    }
}
