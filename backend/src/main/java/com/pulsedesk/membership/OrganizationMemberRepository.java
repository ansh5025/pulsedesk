package com.pulsedesk.membership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    Optional<OrganizationMember> findByUserId(UUID userId);

    Optional<OrganizationMember> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByTeamIdAndActiveTrue(UUID teamId);

    List<OrganizationMember> findByTeamIdAndActiveTrue(UUID teamId);

    List<OrganizationMember> findByOrganizationIdAndActiveTrue(UUID organizationId);
}
