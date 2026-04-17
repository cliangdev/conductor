package com.conductor.repository;

import com.conductor.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, String> {

    List<OrgMember> findByOrgId(String orgId);

    List<OrgMember> findByUserId(String userId);

    Optional<OrgMember> findByOrgIdAndUserId(String orgId, String userId);

    @Query("SELECT COUNT(om) FROM OrgMember om WHERE om.org.id = :orgId AND om.role = :role")
    long countByOrgIdAndRole(@Param("orgId") String orgId, @Param("role") OrgMember.OrgRole role);
}
