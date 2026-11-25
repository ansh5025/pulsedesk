package com.pulsedesk.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, UUID> {

    List<IncidentHistory> findByIncidentIdAndOrganizationIdOrderByCreatedAt(UUID incidentId, UUID organizationId);
}
