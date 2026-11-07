package com.conductor.marketing.growth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface MarketingReviewRepository extends JpaRepository<MarketingReview, String> {

    /** Newest first, reviews with no date last. */
    @Query("select r from MarketingReview r where r.projectId = :projectId and r.appId = :appId "
            + "order by r.reviewedAt desc nulls last, r.reviewId desc")
    List<MarketingReview> findRecent(@Param("projectId") String projectId, @Param("appId") String appId,
                                     Pageable page);

    /** Writes one review unless (project, app, review id) is already stored. Returns 1 when written, else 0. */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO marketing_review (id, project_id, app_id, review_id, rating, title, body, author, reviewed_at)
            VALUES (:id, :projectId, :appId, :reviewId, :rating, :title, :body, :author, :reviewedAt)
            ON CONFLICT (project_id, app_id, review_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("projectId") String projectId,
                       @Param("appId") String appId,
                       @Param("reviewId") String reviewId,
                       @Param("rating") Integer rating,
                       @Param("title") String title,
                       @Param("body") String body,
                       @Param("author") String author,
                       @Param("reviewedAt") OffsetDateTime reviewedAt);
}
