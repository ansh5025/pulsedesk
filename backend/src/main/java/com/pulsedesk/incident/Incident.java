package com.pulsedesk.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "service_id", updatable = false)
    private UUID serviceId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IncidentPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private IncidentSource source;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "created_by_id", updatable = false)
    private UUID createdById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "sla_response_minutes", nullable = false, updatable = false)
    private int slaResponseMinutes;

    @Column(name = "sla_resolution_minutes", nullable = false, updatable = false)
    private int slaResolutionMinutes;

    @Column(name = "sla_response_due_at", nullable = false, updatable = false)
    private Instant slaResponseDueAt;

    @Column(name = "sla_resolution_due_at", nullable = false, updatable = false)
    private Instant slaResolutionDueAt;

    @Column(name = "escalation_level", nullable = false, insertable = false, updatable = false)
    private int escalationLevel;

    @Column(name = "response_escalated_at", insertable = false, updatable = false)
    private Instant responseEscalatedAt;

    @Column(name = "resolution_escalated_at", insertable = false, updatable = false)
    private Instant resolutionEscalatedAt;

    protected Incident() {
    }

    public Incident(UUID organizationId, UUID serviceId, UUID teamId, String title, String description,
                    IncidentPriority priority, IncidentSource source, UUID assigneeId, UUID createdById) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.serviceId = serviceId;
        this.teamId = teamId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.source = source;
        this.assigneeId = assigneeId;
        this.createdById = createdById;
        this.slaResponseMinutes = priority.responseMinutes();
        this.slaResolutionMinutes = priority.resolutionMinutes();
        this.slaResponseDueAt = createdAt.plus(Duration.ofMinutes(slaResponseMinutes));
        this.slaResolutionDueAt = createdAt.plus(Duration.ofMinutes(slaResolutionMinutes));
    }

    /** Applies a status change and stamps the matching lifecycle timestamps. */
    public void moveTo(IncidentStatus next, Instant now) {
        if (next != IncidentStatus.OPEN && acknowledgedAt == null) {
            acknowledgedAt = now;
        }
        if (next == IncidentStatus.RESOLVED) {
            resolvedAt = now;
        } else if (next == IncidentStatus.IN_PROGRESS && status == IncidentStatus.RESOLVED) {
            resolvedAt = null;
        } else if (next == IncidentStatus.CLOSED) {
            closedAt = now;
        }
        status = next;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getServiceId() { return serviceId; }
    public UUID getTeamId() { return teamId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public IncidentStatus getStatus() { return status; }
    public IncidentPriority getPriority() { return priority; }
    public IncidentSource getSource() { return source; }
    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }
    public UUID getCreatedById() { return createdById; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public int getSlaResponseMinutes() { return slaResponseMinutes; }
    public int getSlaResolutionMinutes() { return slaResolutionMinutes; }
    public Instant getSlaResponseDueAt() { return slaResponseDueAt; }
    public Instant getSlaResolutionDueAt() { return slaResolutionDueAt; }
    public int getEscalationLevel() { return escalationLevel; }
    public Instant getResponseEscalatedAt() { return responseEscalatedAt; }
    public Instant getResolutionEscalatedAt() { return resolutionEscalatedAt; }
}
