package com.conductor.repository;

import com.conductor.entity.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, String> {

    List<UserApiKey> findByUserIdAndRevokedAtIsNull(String userId);

    List<UserApiKey> findByUserIdAndRevokedAtIsNullAndLabelStartingWith(String userId, String labelPrefix);

    Optional<UserApiKey> findByKeyHash(String keyHash);
}
