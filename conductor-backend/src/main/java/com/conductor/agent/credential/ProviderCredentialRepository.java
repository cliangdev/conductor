package com.conductor.agent.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, String> {

    Optional<ProviderCredential> findByProjectIdAndProvider(String projectId, String provider);

    boolean existsByProjectIdAndProvider(String projectId, String provider);
}
