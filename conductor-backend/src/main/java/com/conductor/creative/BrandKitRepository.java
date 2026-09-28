package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandKitRepository extends JpaRepository<BrandKit, String> {

    List<BrandKit> findAllByProjectIdOrderByCreatedAtAsc(String projectId);

    Optional<BrandKit> findByProjectIdAndIsDefaultTrue(String projectId);

    Optional<BrandKit> findByIdAndProjectId(String id, String projectId);

    Optional<BrandKit> findByProjectIdAndSlug(String projectId, String slug);

    boolean existsByProjectIdAndSlug(String projectId, String slug);

    long countByProjectId(String projectId);
}
