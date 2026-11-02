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

    Optional<Review> findByWorkItemIdAndReviewerId(String workItemId, String reviewerId);

    List<Review> findAllByWorkItemId(String workItemId);

    boolean existsByWorkItemIdAndVerdict(String workItemId, String verdict);

    /**
     * Whether the Work Item has a review with the given verdict from a project member holding {@code role}
     * (or an ADMIN, who outranks any review role). Backs the Workflow's role-scoped review gate (Wave 4):
     * a transition with {@code reviewerRole: REVIEWER} is satisfied only by a REVIEWER (or ADMIN) approval.
     *
     * <p>Native because the member's roles are a {@code varchar[]} column tested with {@code = ANY(...)}.
     * {@code role} must be a valid {@code MemberRole} name.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM reviews r
                JOIN project_members m ON m.user_id = r.reviewer_id
                WHERE r.work_item_id = :workItemId
                  AND r.verdict = :verdict
                  AND m.project_id = :projectId
                  AND (:role = ANY(m.roles) OR 'ADMIN' = ANY(m.roles))
            )
            """, nativeQuery = true)
    boolean existsApprovedByReviewerRole(@Param("workItemId") String workItemId,
                                         @Param("projectId") String projectId,
                                         @Param("verdict") String verdict,
                                         @Param("role") String role);
}
