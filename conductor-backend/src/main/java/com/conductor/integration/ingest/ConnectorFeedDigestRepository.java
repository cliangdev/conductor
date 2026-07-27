package com.conductor.integration.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectorFeedDigestRepository extends JpaRepository<ConnectorFeedDigest, String> {

    Optional<ConnectorFeedDigest> findByFeedIdAndPeriodKey(String feedId, String periodKey);

    List<ConnectorFeedDigest> findByFeedId(String feedId);

    List<ConnectorFeedDigest> findByStatus(DigestStatus status);
}
