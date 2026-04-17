package com.conductor.service;

import com.conductor.entity.OrgMember;
import com.conductor.entity.Team;
import com.conductor.entity.TeamMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.TeamMemberRepository;
import com.conductor.repository.TeamRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    public record TeamMemberDetails(
            String userId,
            String name,
            String email,
            TeamMember.TeamRole role,
            OffsetDateTime joinedAt
    ) {}

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final UserRepository userRepository;

    public TeamService(
            TeamRepository teamRepository,
            TeamMemberRepository teamMemberRepository,
            OrgRepository orgRepository,
            OrgMemberRepository orgMemberRepository,
            UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.orgRepository = orgRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Team createTeam(String orgId, String creatorUserId, String name) {
        var org = orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        orgMemberRepository.findByOrgIdAndUserId(orgId, creatorUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        if (teamRepository.findByOrgIdAndName(orgId, name).isPresent()) {
            throw new ConflictException("Team name already exists in org: " + name);
        }

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + creatorUserId));

        Team team = new Team();
        team.setOrg(org);
        team.setName(name);
        team.setCreatedBy(creator);
        team = teamRepository.save(team);

        TeamMember lead = new TeamMember();
        lead.setTeam(team);
        lead.setUser(creator);
        lead.setRole(TeamMember.TeamRole.LEAD);
        teamMemberRepository.save(lead);

        return team;
    }

    @Transactional(readOnly = true)
    public List<Team> getTeamsForOrg(String orgId, String requestingUserId) {
        orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        orgMemberRepository.findByOrgIdAndUserId(orgId, requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        return teamRepository.findByOrgId(orgId);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberDetails> getTeamMembers(String teamId, String requestingUserId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + teamId));

        orgMemberRepository.findByOrgIdAndUserId(team.getOrg().getId(), requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        return teamMemberRepository.findByTeamId(teamId).stream()
                .map(tm -> new TeamMemberDetails(
                        tm.getUser().getId(),
                        tm.getUser().getName(),
                        tm.getUser().getEmail(),
                        tm.getRole(),
                        tm.getJoinedAt()
                ))
                .toList();
    }

    @Transactional
    public TeamMember addTeamMember(String teamId, String requestingUserId, String targetUserId, TeamMember.TeamRole role) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + teamId));

        String orgId = team.getOrg().getId();

        OrgMember callerOrgMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        boolean callerIsOrgAdmin = callerOrgMembership.getRole() == OrgMember.OrgRole.ADMIN;
        boolean callerIsTeamLead = teamMemberRepository.findByTeamIdAndUserId(teamId, requestingUserId)
                .map(tm -> tm.getRole() == TeamMember.TeamRole.LEAD)
                .orElse(false);

        if (!callerIsOrgAdmin && !callerIsTeamLead) {
            throw new ForbiddenException("Only team leads or org admins can add team members");
        }

        orgMemberRepository.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new BusinessException("Target user is not an org member"));

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + targetUserId));

        TeamMember member = new TeamMember();
        member.setTeam(team);
        member.setUser(targetUser);
        member.setRole(role);
        return teamMemberRepository.save(member);
    }

    @Transactional
    public void removeTeamMember(String teamId, String requestingUserId, String targetUserId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Team not found: " + teamId));

        String orgId = team.getOrg().getId();

        OrgMember callerOrgMembership = orgMemberRepository.findByOrgIdAndUserId(orgId, requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        boolean callerIsOrgAdmin = callerOrgMembership.getRole() == OrgMember.OrgRole.ADMIN;
        boolean callerIsTeamLead = teamMemberRepository.findByTeamIdAndUserId(teamId, requestingUserId)
                .map(tm -> tm.getRole() == TeamMember.TeamRole.LEAD)
                .orElse(false);

        if (!callerIsOrgAdmin && !callerIsTeamLead) {
            throw new ForbiddenException("Only team leads or org admins can remove team members");
        }

        TeamMember targetMembership = teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found in team: " + targetUserId));

        teamMemberRepository.delete(targetMembership);
    }
}
