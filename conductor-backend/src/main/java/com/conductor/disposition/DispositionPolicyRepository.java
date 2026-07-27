package com.conductor.disposition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DispositionPolicyRepository extends JpaRepository<DispositionPolicy, String> {

    List<DispositionPolicy> findByProjectIdAndEnabledTrue(String projectId);

    Optional<DispositionPolicy> findByProjectIdAndSignalTypeAndDisposition(
            String projectId, String signalType, Disposition disposition);
}
