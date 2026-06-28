package com.conductor.repository;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {

    Optional<Review> findByIssueIdAndReviewerId(String issueId, String reviewerId);

    List<Review> findAllByIssueId(String issueId);

    boolean existsByIssueIdAndVerdict(String issueId, String verdict);

    /**
     * Whether the Work Item has a review with the given verdict from a project member holding {@code role}
     * (or an ADMIN, who outranks any review role). Backs the Workflow's role-scoped review gate (Wave 4):
     * a transition with {@code reviewerRole: REVIEWER} is satisfied only by a REVIEWER (or ADMIN) approval.
     */
    @Query("""
            SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
            FROM Review r, ProjectMember m
            WHERE r.issueId = :issueId
              AND r.verdict = :verdict
              AND m.project.id = :projectId
              AND m.user.id = r.reviewerId
              AND (m.role = :role OR m.role = com.conductor.entity.MemberRole.ADMIN)
            """)
    boolean existsApprovedByReviewerRole(@Param("issueId") String issueId,
                                         @Param("projectId") String projectId,
                                         @Param("verdict") String verdict,
                                         @Param("role") MemberRole role);
}
