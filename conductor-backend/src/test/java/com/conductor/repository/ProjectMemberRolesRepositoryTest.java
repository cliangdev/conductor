package com.conductor.repository;

import com.conductor.entity.Invite;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed coverage for {@code V173__project_member_roles.sql}: the {@code roles varchar[]} column
 * round-trips through the entities (and keeps the legacy {@code role} on the primary role), the CHECK
 * constraint rejects unknown roles, the backfill statement maps {@code role} to a one-element set, and the
 * native queries that read the array (last-admin count, role-scoped review gate) behave. Rides the shared
 * singleton Postgres and isolates by random ids.
 */
@Transactional
class ProjectMemberRolesRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ProjectMemberRepository memberRepository;
    @Autowired private InviteRepository inviteRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ReviewRepository reviewRepository;

    @PersistenceContext private EntityManager em;

    private User owner;
    private Project project;

    @BeforeEach
    void setUp() {
        owner = newUser();
        project = new Project();
        project.setName("Roles Test");
        project.setKey("RT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(owner);
        project = projectRepository.save(project);
    }

    private User newUser() {
        User u = new User();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("U");
        return userRepository.save(u);
    }

    private ProjectMember member(User user, Set<MemberRole> roles) {
        ProjectMember m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRoles(roles);
        return memberRepository.saveAndFlush(m);
    }

    private ProjectMember reload(ProjectMember m) {
        em.clear();
        return memberRepository.findById(m.getId()).orElseThrow();
    }

    private WorkItem newItem() {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("TASK");
        item.setTitle("t");
        item.setSequenceNumber(1);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("OPEN");
        item.setCreatedBy(owner);
        return workItemRepository.saveAndFlush(item);
    }

    private void approve(WorkItem item, User reviewer) {
        Review r = new Review();
        r.setWorkItemId(item.getId());
        r.setReviewerId(reviewer.getId());
        r.setVerdict("APPROVED");
        r.setSubmittedAt(OffsetDateTime.now());
        reviewRepository.saveAndFlush(r);
    }

    @Test
    void aRoleSetRoundTripsAndKeepsTheLegacyRoleOnThePrimary() {
        ProjectMember saved = reload(member(newUser(), Set.of(MemberRole.CREATOR, MemberRole.REVIEWER)));

        assertThat(saved.getRoles()).containsExactlyInAnyOrder(MemberRole.CREATOR, MemberRole.REVIEWER);
        assertThat(saved.getRole()).isEqualTo(MemberRole.CREATOR);
    }

    @Test
    void anEmptySetRoundTripsAndWritesReviewerAsTheLegacyRole() {
        ProjectMember saved = reload(member(newUser(), Set.of()));

        assertThat(saved.getRoles()).isEmpty();
        assertThat(saved.getRole()).isEqualTo(MemberRole.REVIEWER);
    }

    @Test
    void adminIsThePrimaryRoleWhenHeld() {
        ProjectMember saved = reload(member(newUser(), Set.of(MemberRole.REVIEWER, MemberRole.ADMIN)));

        assertThat(saved.getRole()).isEqualTo(MemberRole.ADMIN);
        assertThat(saved.getRoles()).containsExactlyInAnyOrder(MemberRole.ADMIN, MemberRole.REVIEWER);
    }

    @Test
    void theDatabaseRejectsAnUnknownRole() {
        ProjectMember saved = member(newUser(), Set.of(MemberRole.CREATOR));

        assertThatThrownBy(() -> em.createNativeQuery(
                        "UPDATE project_members SET roles = ARRAY['OWNER']::varchar[] WHERE id = :id")
                .setParameter("id", saved.getId()).executeUpdate())
                .hasMessageContaining("project_members_roles_valid");
    }

    @Test
    void theBackfillMapsTheLegacyRoleToAOneElementSet() {
        User u = newUser();
        String id = UUID.randomUUID().toString();
        // A row written by pre-V173 code: legacy role set, roles left at its default.
        em.createNativeQuery("INSERT INTO project_members (id, project_id, user_id, role, joined_at) "
                        + "VALUES (:id, :p, :u, CAST('REVIEWER' AS member_role), now())")
                .setParameter("id", id).setParameter("p", project.getId()).setParameter("u", u.getId())
                .executeUpdate();
        em.createNativeQuery("UPDATE project_members SET roles = ARRAY[role::text] WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        em.clear();

        assertThat(memberRepository.findById(id).orElseThrow().getRoles()).containsExactly(MemberRole.REVIEWER);
    }

    @Test
    void anInviteCarriesARoleSet() {
        Invite invite = new Invite();
        invite.setProject(project);
        invite.setEmail(UUID.randomUUID() + "@example.com");
        invite.setRoles(Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));
        invite.setToken(UUID.randomUUID().toString());
        invite.setInvitedBy(owner);
        invite.setExpiresAt(OffsetDateTime.now().plusDays(1));
        inviteRepository.saveAndFlush(invite);
        em.clear();

        Invite reloaded = inviteRepository.findById(invite.getId()).orElseThrow();
        assertThat(reloaded.getRoles()).containsExactlyInAnyOrder(MemberRole.CREATOR, MemberRole.REVIEWER);
        assertThat(reloaded.getRole()).isEqualTo(MemberRole.CREATOR);
    }

    @Test
    void countAdminsCountsAnyMemberHoldingAdmin() {
        member(owner, Set.of(MemberRole.ADMIN));
        member(newUser(), Set.of(MemberRole.ADMIN, MemberRole.REVIEWER));
        member(newUser(), Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));

        assertThat(memberRepository.countAdmins(project.getId())).isEqualTo(2);
    }

    @Test
    void theRoleScopedReviewQueryMatchesAnyHeldRole() {
        WorkItem item = newItem();
        User editorOnly = newUser();
        member(editorOnly, Set.of(MemberRole.CREATOR));
        User editorReviewer = newUser();
        member(editorReviewer, Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));

        approve(item, editorOnly);
        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER", null))
                .isFalse();

        approve(item, editorReviewer);
        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER", null))
                .isTrue();
    }

    @Test
    void anAdminsApprovalSatisfiesAnyReviewerRole() {
        WorkItem item = newItem();
        User admin = newUser();
        member(admin, Set.of(MemberRole.ADMIN));
        approve(item, admin);

        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER", null))
                .isTrue();
    }

    // --- the sync trigger: old code writes only `role` ---

    private String rolesOf(String table, String id) {
        em.clear();
        return (String) em.createNativeQuery("SELECT array_to_string(roles, ',') FROM " + table + " WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    @Test
    void anInsertThatWritesOnlyRoleGetsTheMatchingRolesSet() {
        String id = UUID.randomUUID().toString();
        em.createNativeQuery("INSERT INTO project_members (id, project_id, user_id, role, joined_at) "
                        + "VALUES (:id, :p, :u, CAST('ADMIN' AS member_role), now())")
                .setParameter("id", id).setParameter("p", project.getId()).setParameter("u", newUser().getId())
                .executeUpdate();

        assertThat(rolesOf("project_members", id)).isEqualTo("ADMIN");
    }

    @Test
    void anUpdateThatChangesOnlyRoleMovesRolesWithIt() {
        ProjectMember m = member(newUser(), Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));

        em.createNativeQuery("UPDATE project_members SET role = CAST('REVIEWER' AS member_role) WHERE id = :id")
                .setParameter("id", m.getId()).executeUpdate();

        assertThat(rolesOf("project_members", m.getId())).isEqualTo("REVIEWER");
    }

    @Test
    void anUpdateThatChangesBothColumnsIsLeftAlone() {
        ProjectMember m = member(newUser(), Set.of(MemberRole.CREATOR));

        em.createNativeQuery("UPDATE project_members SET role = CAST('CREATOR' AS member_role), "
                        + "roles = ARRAY['CREATOR','REVIEWER']::varchar[] WHERE id = :id")
                .setParameter("id", m.getId()).executeUpdate();

        assertThat(rolesOf("project_members", m.getId())).isEqualTo("CREATOR,REVIEWER");
    }

    @Test
    void newCodeInsertingAViewerKeepsTheEmptySet() {
        ProjectMember viewer = member(newUser(), Set.of());

        assertThat(rolesOf("project_members", viewer.getId())).isEmpty();
    }

    @Test
    void aNullRolesRowReadsAsTheEmptySet() {
        ProjectMember m = member(newUser(), Set.of(MemberRole.CREATOR));
        em.createNativeQuery("UPDATE project_members SET roles = NULL, joined_at = now() WHERE id = :id")
                .setParameter("id", m.getId()).executeUpdate();
        em.clear();

        assertThat(memberRepository.findById(m.getId()).orElseThrow().getRoles()).isEmpty();
    }

    @Test
    void aDeliberatelyEmptySetSurvivesLaterWritesOfTheSameRole() {
        ProjectMember viewer = member(newUser(), Set.of());

        em.createNativeQuery("UPDATE project_members SET joined_at = now() WHERE id = :id")
                .setParameter("id", viewer.getId()).executeUpdate();

        assertThat(rolesOf("project_members", viewer.getId())).isEmpty();
    }

    @Test
    void theSameTriggerCoversInvites() {
        String id = UUID.randomUUID().toString();
        em.createNativeQuery("INSERT INTO invites (id, project_id, email, role, token, invited_by, status, expires_at, created_at) "
                        + "VALUES (:id, :p, :e, CAST('CREATOR' AS member_role), :t, :u, 'PENDING', now() + interval '1 day', now())")
                .setParameter("id", id).setParameter("p", project.getId())
                .setParameter("e", UUID.randomUUID() + "@example.com").setParameter("t", UUID.randomUUID().toString())
                .setParameter("u", owner.getId()).executeUpdate();
        assertThat(rolesOf("invites", id)).isEqualTo("CREATOR");

        em.createNativeQuery("UPDATE invites SET role = CAST('REVIEWER' AS member_role) WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        assertThat(rolesOf("invites", id)).isEqualTo("REVIEWER");
    }

    // --- excluding the author from the review gate ---

    @Test
    void theAuthorsApprovalIsIgnoredWhenTheyAreExcluded() {
        WorkItem item = newItem();
        User author = newUser();
        member(author, Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));
        approve(item, author);

        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER", null))
                .isTrue();
        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER",
                author.getId())).isFalse();
        assertThat(reviewRepository.existsByWorkItemIdAndVerdict(item.getId(), "APPROVED", null)).isTrue();
        assertThat(reviewRepository.existsByWorkItemIdAndVerdict(item.getId(), "APPROVED", author.getId())).isFalse();
    }

    @Test
    void anotherReviewersApprovalCountsWhenTheAuthorIsExcluded() {
        WorkItem item = newItem();
        User author = newUser();
        member(author, Set.of(MemberRole.CREATOR, MemberRole.REVIEWER));
        User other = newUser();
        member(other, Set.of(MemberRole.REVIEWER));
        approve(item, author);
        approve(item, other);

        assertThat(reviewRepository.existsApprovedByReviewerRole(item.getId(), project.getId(), "APPROVED", "REVIEWER",
                author.getId())).isTrue();
        assertThat(reviewRepository.existsByWorkItemIdAndVerdict(item.getId(), "APPROVED", author.getId())).isTrue();
    }
}
