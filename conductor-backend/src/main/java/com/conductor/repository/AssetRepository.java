package com.conductor.repository;

import com.conductor.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findAllByIssueId(String issueId);

    Optional<Asset> findByIdAndIssueId(String id, String issueId);

    boolean existsByIssueIdAndTypeAndRef(String issueId, String type, String ref);
}
