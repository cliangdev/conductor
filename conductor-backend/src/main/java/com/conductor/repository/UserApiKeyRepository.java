package com.conductor.repository;

import com.conductor.entity.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, String> {

    List<UserApiKey> findByUserIdAndRevokedAtIsNull(String userId);

    List<UserApiKey> findByUserIdAndRevokedAtIsNullAndLabelStartingWith(String userId, String labelPrefix);

    @Query("SELECT k FROM UserApiKey k JOIN FETCH k.user WHERE k.keyValue = :keyValue")
    Optional<UserApiKey> findByKeyValueWithUser(@Param("keyValue") String keyValue);
}
