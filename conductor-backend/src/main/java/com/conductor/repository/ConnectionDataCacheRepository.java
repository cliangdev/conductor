package com.conductor.repository;

import com.conductor.entity.ConnectionDataCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConnectionDataCacheRepository extends JpaRepository<ConnectionDataCache, String> {
    Optional<ConnectionDataCache> findByConnectionId(String connectionId);
    void deleteByConnectionId(String connectionId);
}
