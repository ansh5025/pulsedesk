package com.pulsedesk.incident;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Incident> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable page);

    List<Incident> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID organizationId, IncidentStatus status,
                                                                     Pageable page);

    List<Incident> findByOrganizationIdAndServiceIdOrderByCreatedAtDesc(UUID organizationId, UUID serviceId,
                                                                        Pageable page);

    List<Incident> findByOrganizationIdAndServiceIdAndSourceAndStatusIn(UUID organizationId, UUID serviceId,
                                                                        IncidentSource source,
                                                                        List<IncidentStatus> statuses);

    @Query("select i from Incident i where i.status = com.pulsedesk.incident.IncidentStatus.OPEN "
            + "and i.acknowledgedAt is null and i.responseEscalatedAt is null and i.slaResponseDueAt < :now "
            + "order by i.slaResponseDueAt")
    List<Incident> findResponseBreached(@Param("now") Instant now, Pageable page);

    @Query("select i from Incident i where i.status in (com.pulsedesk.incident.IncidentStatus.OPEN, "
            + "com.pulsedesk.incident.IncidentStatus.ACKNOWLEDGED, com.pulsedesk.incident.IncidentStatus.IN_PROGRESS) "
            + "and i.resolutionEscalatedAt is null and i.slaResolutionDueAt < :now order by i.slaResolutionDueAt")
    List<Incident> findResolutionBreached(@Param("now") Instant now, Pageable page);

    /** Atomic claim: only one caller (even across instances) wins the right to escalate. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Incident i set i.responseEscalatedAt = :now, i.escalationLevel = i.escalationLevel + 1 "
            + "where i.id = :id and i.responseEscalatedAt is null")
    int claimResponseEscalation(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Incident i set i.resolutionEscalatedAt = :now, i.escalationLevel = i.escalationLevel + 1 "
            + "where i.id = :id and i.resolutionEscalatedAt is null")
    int claimResolutionEscalation(@Param("id") UUID id, @Param("now") Instant now);
}
