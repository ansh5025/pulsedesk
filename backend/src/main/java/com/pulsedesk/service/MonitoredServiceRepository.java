package com.pulsedesk.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    Optional<MonitoredService> findByIdAndOrganizationIdAndActiveTrue(UUID id, UUID organizationId);

    List<MonitoredService> findByOrganizationIdAndActiveTrueOrderByName(UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
