package com.conductor.repository;

import com.conductor.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findAllByWorkItemId(String workItemId);

    Optional<Asset> findByIdAndWorkItemId(String id, String workItemId);

    boolean existsByWorkItemIdAndTypeAndRef(String workItemId, String type, String ref);
}
