package com.pulsedesk.monitoring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HealthCheckConfigRepository extends JpaRepository<HealthCheckConfig, UUID> {

    Optional<HealthCheckConfig> findByServiceIdAndOrganizationId(UUID serviceId, UUID organizationId);

    List<HealthCheckConfig> findByServiceIdIn(List<UUID> serviceIds);

    @Query(nativeQuery = true, value = """
            SELECT * FROM health_check_configs
            WHERE enabled
              AND (last_checked_at IS NULL
                   OR last_checked_at + make_interval(secs => interval_seconds) <= now())
            """)
    List<HealthCheckConfig> findDue();
}
