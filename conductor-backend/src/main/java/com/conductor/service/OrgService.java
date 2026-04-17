package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Organization;
import com.conductor.entity.OrgMember;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.OrgRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;

@Service
public class OrgService {

    private static final Logger log = LoggerFactory.getLogger(OrgService.class);

    private final OrgRepository orgRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    public OrgService(
            OrgRepository orgRepository,
            OrgMemberRepository orgMemberRepository,
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            UserRepository userRepository) {
        this.orgRepository = orgRepository;
        this.orgMemberRepository = orgMemberRepository;
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Organization createOrg(String creatorUserId, String name, String slug) {
        if (orgRepository.findBySlug(slug).isPresent()) {
            throw new ConflictException("Slug already taken: " + slug);
        }

        boolean isFirstOrg = orgMemberRepository.findByUserId(creatorUserId).isEmpty();

        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + creatorUserId));

        Organization org = new Organization();
        org.setName(name);
        org.setSlug(slug);
        org.setCreatedBy(creator);
        org = orgRepository.save(org);

        OrgMember member = new OrgMember();
        member.setOrg(org);
        member.setUser(creator);
        member.setRole(OrgMember.OrgRole.ADMIN);
        orgMemberRepository.save(member);

        if (isFirstOrg) {
            migrateExistingProjects(creatorUserId, org.getId());
        }

        return org;
    }

    @Transactional(readOnly = true)
    public Organization getOrg(String orgId, String requestingUserId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new EntityNotFoundException("Org not found: " + orgId));

        orgMemberRepository.findByOrgIdAndUserId(orgId, requestingUserId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this org"));

        return org;
    }

    @Transactional(readOnly = true)
    public List<Organization> getOrgsForUser(String userId) {
        return orgMemberRepository.findByUserId(userId).stream()
                .map(OrgMember::getOrg)
                .toList();
    }

    @Transactional
    public Organization getOrCreatePersonalOrg(String userId, String displayName, String email) {
        List<OrgMember> memberships = orgMemberRepository.findByUserId(userId);
        if (!memberships.isEmpty()) {
            return memberships.get(0).getOrg();
        }

        String slug = generatePersonalSlug(displayName);
        String name = "Personal Workspace";

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        Organization org = new Organization();
        org.setName(name);
        org.setSlug(slug);
        org.setCreatedBy(user);
        org = orgRepository.save(org);

        OrgMember member = new OrgMember();
        member.setOrg(org);
        member.setUser(user);
        member.setRole(OrgMember.OrgRole.ADMIN);
        orgMemberRepository.save(member);

        migrateExistingProjects(userId, org.getId());

        return org;
    }

    @Transactional
    public void migrateExistingProjects(String userId, String orgId) {
        // Filter by role in Java to avoid PG enum cast issue (member_role vs varchar)
        List<ProjectMember> adminMemberships = projectMemberRepository.findByUserId(userId).stream()
                .filter(pm -> pm.getRole() == MemberRole.ADMIN)
                .toList();
        for (ProjectMember pm : adminMemberships) {
            Project project = pm.getProject();
            if (project.getOrgId() == null) {
                project.setOrgId(orgId);
                projectRepository.save(project);
            }
        }
    }

    private String generatePersonalSlug(String displayName) {
        String base = displayName == null ? "workspace" : displayName
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (base.isEmpty()) {
            base = "workspace";
        }

        String suffix = randomSuffix();
        String candidate = base + "-" + suffix;

        // ensure uniqueness — retry up to 5 times if somehow collides
        for (int i = 0; i < 5; i++) {
            if (orgRepository.findBySlug(candidate).isEmpty()) {
                return candidate;
            }
            candidate = base + "-" + randomSuffix();
        }
        return candidate;
    }

    private String randomSuffix() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
