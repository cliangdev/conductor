package com.conductor.repository;

import com.conductor.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {

    Optional<Review> findByWorkItemIdAndReviewerId(String issueId, String reviewerId);

    List<Review> findAllByWorkItemId(String issueId);

    boolean existsByWorkItemIdAndVerdict(String issueId, String verdict);

    /**
     * Whether the Work Item has a review with the given verdict from a project member holding {@code role}
     * (or an ADMIN, who outranks any review role). Backs the Workflow's role-scoped review gate (Wave 4):
     * a transition with {@code reviewerRole: REVIEWER} is satisfied only by a REVIEWER (or ADMIN) approval.
     *
     * <p>Native because {@code project_members.role} is a native PostgreSQL enum ({@code member_role}); a
     * JPQL/bound comparison binds the role as {@code varchar}, which Postgres won't compare to the enum
     * ("operator does not exist: member_role = character varying"). The bound, caller-validated role string is
     * cast explicitly. {@code role} must be a valid {@code MemberRole} name.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM reviews r
                JOIN project_members m ON m.user_id = r.reviewer_id
                WHERE r.work_item_id = :issueId
                  AND r.verdict = :verdict
                  AND m.project_id = :projectId
                  AND (m.role = CAST(:role AS member_role) OR m.role = CAST('ADMIN' AS member_role))
            )
            """, nativeQuery = true)
    boolean existsApprovedByReviewerRole(@Param("issueId") String issueId,
                                         @Param("projectId") String projectId,
                                         @Param("verdict") String verdict,
                                         @Param("role") String role);
}
