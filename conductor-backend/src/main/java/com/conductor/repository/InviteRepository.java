package com.conductor.repository;

import com.conductor.entity.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InviteRepository extends JpaRepository<Invite, String> {

    Optional<Invite> findByToken(String token);

    Optional<Invite> findByProjectIdAndEmailAndStatus(String projectId, String email, String status);

    List<Invite> findByProjectIdAndStatus(String projectId, String status);

    /**
     * Stores an empty role set. V173's sync trigger reads an empty set on INSERT as "written by old code"
     * and fills it from {@code role}, so a deliberate read-only invite is cleared with an UPDATE (which the
     * trigger leaves alone) right after the insert.
     */
    @Modifying
    @Query(value = "UPDATE invites SET roles = '{}' WHERE id = :id", nativeQuery = true)
    void clearRoles(@Param("id") String id);

}
