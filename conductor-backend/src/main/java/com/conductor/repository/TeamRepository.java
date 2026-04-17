package com.conductor.repository;

import com.conductor.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, String> {

    List<Team> findByOrgId(String orgId);

    Optional<Team> findByOrgIdAndName(String orgId, String name);
}
