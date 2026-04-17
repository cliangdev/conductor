package com.conductor.repository;

import com.conductor.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, String> {

    List<TeamMember> findByTeamId(String teamId);

    List<TeamMember> findByUserId(String userId);

    Optional<TeamMember> findByTeamIdAndUserId(String teamId, String userId);
}
