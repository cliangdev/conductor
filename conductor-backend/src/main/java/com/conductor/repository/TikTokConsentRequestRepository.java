package com.conductor.repository;

import com.conductor.entity.TikTokConsentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Consent requests (V153). Always read through the project, so an id from another project is just absent. */
@Repository
public interface TikTokConsentRequestRepository extends JpaRepository<TikTokConsentRequest, String> {

    Optional<TikTokConsentRequest> findByIdAndProjectId(String id, String projectId);
}
