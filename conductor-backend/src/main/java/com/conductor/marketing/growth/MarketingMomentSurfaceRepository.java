package com.conductor.marketing.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingMomentSurfaceRepository extends JpaRepository<MarketingMomentSurface, String> {

    List<MarketingMomentSurface> findByMomentIdInOrderByDueOnAscSurfaceAsc(Collection<String> momentIds);

    Optional<MarketingMomentSurface> findByIdAndMomentId(String id, String momentId);
}
