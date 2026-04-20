package com.conductor.repository;

import com.conductor.entity.OrgInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrgInviteRepository extends JpaRepository<OrgInvite, String> {

    Optional<OrgInvite> findByToken(String token);

    Optional<OrgInvite> findByOrgIdAndEmailAndStatus(String orgId, String email, String status);

    List<OrgInvite> findByOrgIdAndStatus(String orgId, String status);
}
