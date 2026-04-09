package com.conductor.repository;

import com.conductor.entity.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InviteRepository extends JpaRepository<Invite, String> {

    Optional<Invite> findByToken(String token);

    Optional<Invite> findByProjectIdAndEmailAndStatus(String projectId, String email, String status);
}
