package com.conductor.service;

import com.conductor.entity.OrgInvite;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Organization;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgInviteRepository;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.TeamMemberRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class OrgMemberServiceTest {

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private OrgInviteRepository orgInviteRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @InjectMocks
    private OrgMemberService orgMemberService;

    private Organization testOrg;
    private User adminUser;
    private User memberUser;
    private OrgMember adminMembership;
    private OrgMember memberMembership;

    @BeforeEach
    void setUp() {
        testOrg = new Organization();
        testOrg.setId("org-1");
        testOrg.setName("Test Org");
        testOrg.setSlug("test-org");

        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setEmail("admin@example.com");
        adminUser.setName("Admin User");

        memberUser = new User();
        memberUser.setId("member-1");
        memberUser.setEmail("member@example.com");
        memberUser.setName("Member User");

        adminMembership = new OrgMember();
        adminMembership.setId("om-admin");
        adminMembership.setOrg(testOrg);
        adminMembership.setUser(adminUser);
        adminMembership.setRole(OrgMember.OrgRole.ADMIN);
        adminMembership.setJoinedAt(OffsetDateTime.now());

        memberMembership = new OrgMember();
        memberMembership.setId("om-member");
        memberMembership.setOrg(testOrg);
        memberMembership.setUser(memberUser);
        memberMembership.setRole(OrgMember.OrgRole.MEMBER);
        memberMembership.setJoinedAt(OffsetDateTime.now());
    }

    // getMembers tests

    @Test
    void getMembers_returnsAllMembersForOrgMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgId("org-1"))
                .thenReturn(List.of(adminMembership, memberMembership));

        List<OrgMemberService.OrgMemberDetails> result = orgMemberService.getMembers("org-1", "admin-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userId()).isEqualTo("admin-1");
        assertThat(result.get(0).role()).isEqualTo(OrgMember.OrgRole.ADMIN);
        assertThat(result.get(1).userId()).isEqualTo("member-1");
    }

    @Test
    void getMembers_throwsForbiddenForNonMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "outsider"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orgMemberService.getMembers("org-1", "outsider"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getMembers_throwsNotFoundForMissingOrg() {
        when(orgRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orgMemberService.getMembers("nonexistent", "admin-1"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // changeMemberRole tests

    @Test
    void changeMemberRole_updatesRoleSuccessfully() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));
        when(orgMemberRepository.save(any(OrgMember.class))).thenReturn(memberMembership);

        OrgMemberService.OrgMemberDetails result = orgMemberService.changeMemberRole(
                "org-1", "admin-1", "member-1", OrgMember.OrgRole.ADMIN);

        assertThat(result.userId()).isEqualTo("member-1");
        assertThat(result.role()).isEqualTo(OrgMember.OrgRole.ADMIN);
        verify(orgMemberRepository).save(memberMembership);
    }

    @Test
    void changeMemberRole_throwsForbiddenForNonAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));

        assertThatThrownBy(() -> orgMemberService.changeMemberRole(
                "org-1", "member-1", "admin-1", OrgMember.OrgRole.MEMBER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void changeMemberRole_throwsBusinessExceptionWhenDemotingLastAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.countByOrgIdAndRole("org-1", OrgMember.OrgRole.ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> orgMemberService.changeMemberRole(
                "org-1", "admin-1", "admin-1", OrgMember.OrgRole.MEMBER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last admin");
    }

    // removeMember tests

    @Test
    void removeMember_removesNonAdminMemberSuccessfully() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));

        orgMemberService.removeMember("org-1", "admin-1", "member-1");

        verify(orgMemberRepository).delete(memberMembership);
    }

    @Test
    void removeMember_throwsBusinessExceptionWhenRemovingLastAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.countByOrgIdAndRole("org-1", OrgMember.OrgRole.ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> orgMemberService.removeMember("org-1", "admin-1", "admin-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last admin");
    }

    @Test
    void removeMember_throwsForbiddenWhenNonAdminRemovesOther() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));

        assertThatThrownBy(() -> orgMemberService.removeMember("org-1", "member-1", "admin-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void removeMember_allowsSelfRemovalByNonAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));

        orgMemberService.removeMember("org-1", "member-1", "member-1");

        verify(orgMemberRepository).delete(memberMembership);
    }

    @Test
    void removeMember_throwsNotFoundForMissingMember() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orgMemberService.removeMember("org-1", "admin-1", "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // inviteMember tests

    @Test
    void inviteMember_throwsForbiddenForNonAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "member-1"))
                .thenReturn(Optional.of(memberMembership));

        assertThatThrownBy(() -> orgMemberService.inviteMember(
                "org-1", "member-1", "new@example.com", OrgMember.OrgRole.MEMBER))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void inviteMember_sendsEmailForAdmin() {
        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(adminUser));
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(orgInviteRepository.findByOrgIdAndEmailAndStatus("org-1", "new@example.com", "PENDING"))
                .thenReturn(Optional.empty());
        when(orgInviteRepository.save(any(OrgInvite.class))).thenAnswer(i -> i.getArgument(0));

        orgMemberService.inviteMember("org-1", "admin-1", "new@example.com", OrgMember.OrgRole.MEMBER);

        verify(emailService).sendOrgInviteEmail("new@example.com", "Admin User", "Test Org");
    }

    @Test
    void inviteMember_throwsBusinessExceptionForExistingMember() {
        User existingUser = new User();
        existingUser.setId("existing-1");
        OrgMember existingMembership = new OrgMember();
        existingMembership.setUser(existingUser);
        existingMembership.setOrg(testOrg);

        when(orgRepository.findById("org-1")).thenReturn(Optional.of(testOrg));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "admin-1"))
                .thenReturn(Optional.of(adminMembership));
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(adminUser));
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(existingUser));
        when(orgMemberRepository.findByOrgIdAndUserId("org-1", "existing-1"))
                .thenReturn(Optional.of(existingMembership));

        assertThatThrownBy(() -> orgMemberService.inviteMember(
                "org-1", "admin-1", "existing@example.com", OrgMember.OrgRole.MEMBER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a member");
    }
}
