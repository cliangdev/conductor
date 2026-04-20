package com.conductor.service;

import com.conductor.entity.OrgInvite;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.InviteExpiredException;
import com.conductor.generated.model.AcceptOrgInviteResponse;
import com.conductor.repository.OrgInviteRepository;
import com.conductor.repository.OrgMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class OrgInviteService {

    private final OrgInviteRepository orgInviteRepository;
    private final OrgMemberRepository orgMemberRepository;

    public OrgInviteService(OrgInviteRepository orgInviteRepository, OrgMemberRepository orgMemberRepository) {
        this.orgInviteRepository = orgInviteRepository;
        this.orgMemberRepository = orgMemberRepository;
    }

    @Transactional
    public AcceptOrgInviteResponse acceptInvite(String token, User currentUser) {
        OrgInvite invite = orgInviteRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Invite not found"));

        if (!"PENDING".equals(invite.getStatus())) {
            throw new ConflictException("Invite already used");
        }

        if (invite.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InviteExpiredException("Invite has expired");
        }

        Organization org = invite.getOrg();

        orgMemberRepository.findByOrgIdAndUserId(org.getId(), currentUser.getId()).ifPresent(existing -> {
            throw new ConflictException("You are already a member of this organization");
        });

        OrgMember member = new OrgMember();
        member.setOrg(org);
        member.setUser(currentUser);
        member.setRole(invite.getRole());
        orgMemberRepository.save(member);

        invite.setStatus("ACCEPTED");
        orgInviteRepository.save(invite);

        return new AcceptOrgInviteResponse(org.getId(), org.getName(), invite.getRole().name());
    }
}
