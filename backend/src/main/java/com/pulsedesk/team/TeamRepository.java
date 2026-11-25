package com.pulsedesk.team;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Team> findByOrganizationIdOrderByName(UUID organizationId);

    Optional<Team> findByTeamLeadId(UUID teamLeadId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
