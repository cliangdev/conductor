package com.conductor.creative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreativePhotoRepository extends JpaRepository<CreativePhoto, String> {

    Optional<CreativePhoto> findByIdAndProjectId(String id, String projectId);

    List<CreativePhoto> findAllByProjectIdOrderByCreatedAtDesc(String projectId);

    List<CreativePhoto> findAllByProjectIdAndBlockedFalseOrderByCreatedAtDesc(String projectId);

    List<CreativePhoto> findAllByIdIn(Collection<String> ids);
}
