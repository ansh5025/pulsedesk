package com.pulsedesk.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndRecipientIdAndOrganizationId(UUID id, UUID recipientId, UUID organizationId);

    List<Notification> findByRecipientIdAndOrganizationIdOrderByCreatedAtDesc(UUID recipientId, UUID organizationId,
                                                                              Pageable page);

    List<Notification> findByRecipientIdAndOrganizationIdAndReadAtIsNullOrderByCreatedAtDesc(
            UUID recipientId, UUID organizationId, Pageable page);

    long countByRecipientIdAndOrganizationIdAndReadAtIsNull(UUID recipientId, UUID organizationId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientId = :recipient "
            + "and n.organizationId = :org and n.readAt is null")
    int markAllRead(@Param("recipient") UUID recipientId, @Param("org") UUID organizationId,
                    @Param("now") Instant now);
}
