package com.conductor.repository;

import com.conductor.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgRepository extends JpaRepository<Organization, String> {

    Optional<Organization> findBySlug(String slug);
}
