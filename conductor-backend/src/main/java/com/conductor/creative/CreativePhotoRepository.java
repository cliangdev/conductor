package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativePhotoRepository extends JpaRepository<CreativePhoto, String> {

    Optional<CreativePhoto> findByIdAndProjectId(String id, String projectId);

    List<CreativePhoto> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    List<CreativePhoto> findAllByProjectIdAndBlockedFalseOrderByCreatedAtDesc(String projectId);

    /** (id, gcsPath) of every Media item in the project, blocked or not — a projection, not whole rows. */
    @Query("select p.id, p.gcsPath from CreativePhoto p where p.projectId = :projectId")
    List<Object[]> findPathsByProjectId(@Param("projectId") String projectId);

    List<CreativePhoto> findAllByIdIn(Collection<String> ids);

    /** Blocked Media whose id is in {@code ids} or whose storage path is in {@code paths} (both non-empty). */
    @Query("select p from CreativePhoto p where p.blocked = true and (p.id in :ids or p.gcsPath in :paths)")
    List<CreativePhoto> findBlockedByIdsOrPaths(@Param("ids") Collection<String> ids,
                                                @Param("paths") Collection<String> paths);
}
