package com.conductor.service;

import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.Team;
import com.conductor.entity.TeamMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.TeamMemberRepository;
import com.conductor.repository.TeamRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TeamService teamService;

    private User adminUser;
    private User memberUser;
    private Organization testOrg;
    private Team testTeam;
    private OrgMember adminMembership;
    private OrgMember memberMembership;
    private TeamMember leadMembership;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("user-admin");
        adminUser.setEmail("admin@example.com");
        adminUser.setName("Admin User");

        memberUser = new User();
        memberUser.setId("user-member");
        memberUser.setEmail("member@example.com");
        memberUser.setName("Member User");

        testOrg = new Organization();
        testOrg.setId("org-1");
        testOrg.setName("Test Org");
        testOrg.setSlug("test-org");
        testOrg.setCreatedBy(adminUser);
        testOrg.setCreatedAt(OffsetDateTime.now());

        testTeam = new Team();
        testTeam.setId("team-1");
        testTeam.setOrg(testOrg);
        testTeam.setName("Engineering");
        testTeam.setCreatedBy(adminUser);
        testTeam.setCreatedAt(OffsetDateTime.now());

        adminMembership = new OrgMember();
        adminMembership.setOrg(testOrg);
        adminMembership.setUser(adminUser);
        adminMembership.setRole(OrgMember.OrgRole.ADMIN);
        adminMembership.setJoinedAt(OffsetDateTime.now());

        memberMembership = new OrgMember();
        memberMembership.setOrg(testOrg);
        memberMembership.setUser(memberUser);
        memberMembership.setRole(OrgMember.OrgRole.MEMBER);
        memberMembership.setJoinedAt(OffsetDateTime.now());

        leadMembership = new TeamMember();
        leadMembership.setId("tm-1");
        leadMembership.setTeam(testTeam);
        leadMembership.setUser(adminUser);
        leadMembership.setRole(TeamMember.TeamRole.LEAD);
        leadMembership.setJoinedAt(OffsetDateTime.now());
    }

    // ---- createTeam ----

    @Test
    void createTeam_savesTeamAndAssignsCreatorAsLead() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamRepository.findByOrgIdAndName("org-1", "Engineering"))
                .thenReturn(Optional.empty());
        when(userRepository.findById("user-admin")).thenReturn(Optional.of(adminUser));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> {
            Team t = inv.getArgument(0);
            t.setId("team-new");
            t.setCreatedAt(OffsetDateTime.now());
            return t;
        });

        Team result = teamService.createTeam("org-1", "user-admin", "Engineering");

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Engineering");

        ArgumentCaptor<TeamMember> memberCaptor = ArgumentCaptor.forClass(TeamMember.class);
        verify(teamMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(TeamMember.TeamRole.LEAD);
        assertThat(memberCaptor.getValue().getUser()).isEqualTo(adminUser);
    }

    @Test
    void createTeam_throwsConflictOnDuplicateNameInSameOrg() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamRepository.findByOrgIdAndName("org-1", "Engineering"))
                .thenReturn(Optional.of(testTeam));

        assertThatThrownBy(() -> teamService.createTeam("org-1", "user-admin", "Engineering"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Engineering");
    }

    @Test
    void createTeam_throwsForbiddenWhenCreatorNotOrgMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.createTeam("org-1", "user-stranger", "Engineering"))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- getTeamsForOrg ----

    @Test
    void getTeamsForOrg_returnsTeamsForOrgMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamRepository.findByOrgId("org-1")).thenReturn(List.of(testTeam));

        List<Team> result = teamService.getTeamsForOrg("org-1", "user-admin");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Engineering");
    }

    @Test
    void getTeamsForOrg_throwsForbiddenWhenNotMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeamsForOrg("org-1", "user-stranger"))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- getTeamMembers ----

    @Test
    void getTeamMembers_returnsMembersWithUserDetails() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamMemberRepository.findByTeamId("team-1")).thenReturn(List.of(leadMembership));

        List<TeamService.TeamMemberDetails> result = teamService.getTeamMembers("team-1", "user-admin");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo("user-admin");
        assertThat(result.get(0).role()).isEqualTo(TeamMember.TeamRole.LEAD);
    }

    @Test
    void getTeamMembers_throwsForbiddenWhenNotOrgMember() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeamMembers("team-1", "user-stranger"))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- addTeamMember ----

    @Test
    void addTeamMember_throwsBusinessExceptionWhenTargetNotOrgMember() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-stranger"))
                .thenReturn(Optional.empty());

        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-admin"))
                .thenReturn(Optional.of(leadMembership));

        assertThatThrownBy(() -> teamService.addTeamMember("team-1", "user-admin", "user-stranger", TeamMember.TeamRole.MEMBER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("org member");
    }

    @Test
    void addTeamMember_throwsForbiddenWhenCallerNotLeadOrAdmin() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-member"))
                .thenReturn(Optional.of(memberMembership));
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-member"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.addTeamMember("team-1", "user-member", "user-admin", TeamMember.TeamRole.MEMBER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addTeamMember_savesTeamMemberWhenCallerIsLead() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-admin"))
                .thenReturn(Optional.of(leadMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-member"))
                .thenReturn(Optional.of(memberMembership));
        when(userRepository.findById("user-member")).thenReturn(Optional.of(memberUser));
        when(teamMemberRepository.save(any(TeamMember.class))).thenAnswer(inv -> {
            TeamMember tm = inv.getArgument(0);
            tm.setId("tm-new");
            tm.setJoinedAt(OffsetDateTime.now());
            return tm;
        });

        TeamMember result = teamService.addTeamMember("team-1", "user-admin", "user-member", TeamMember.TeamRole.MEMBER);

        assertThat(result.getRole()).isEqualTo(TeamMember.TeamRole.MEMBER);
        assertThat(result.getUser()).isEqualTo(memberUser);
    }

    // ---- removeTeamMember ----

    @Test
    void removeTeamMember_removesWhenCallerIsLead() {
        TeamMember targetMembership = new TeamMember();
        targetMembership.setId("tm-2");
        targetMembership.setTeam(testTeam);
        targetMembership.setUser(memberUser);
        targetMembership.setRole(TeamMember.TeamRole.MEMBER);
        targetMembership.setJoinedAt(OffsetDateTime.now());

        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-admin"))
                .thenReturn(Optional.of(adminMembership));
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-admin"))
                .thenReturn(Optional.of(leadMembership));
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-member"))
                .thenReturn(Optional.of(targetMembership));

        teamService.removeTeamMember("team-1", "user-admin", "user-member");

        verify(teamMemberRepository).delete(targetMembership);
    }

    @Test
    void removeTeamMember_throwsForbiddenWhenCallerNotLeadOrAdmin() {
        when(teamRepository.findById("team-1")).thenReturn(Optional.of(testTeam));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "user-member"))
                .thenReturn(Optional.of(memberMembership));
        when(teamMemberRepository.findByTeamIdAndUserId("team-1", "user-member"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.removeTeamMember("team-1", "user-member", "user-admin"))
                .isInstanceOf(ForbiddenException.class);
    }
}
