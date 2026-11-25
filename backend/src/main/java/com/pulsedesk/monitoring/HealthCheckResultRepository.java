package com.pulsedesk.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HealthCheckResultRepository extends JpaRepository<HealthCheckResult, UUID> {

    List<HealthCheckResult> findByConfigIdAndOrganizationIdOrderByCheckedAtDesc(UUID configId, UUID organizationId,
                                                                                Pageable pageable);

    @Modifying
    @Transactional
    @Query("delete from HealthCheckResult r where r.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
