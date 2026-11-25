package com.pulsedesk.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident_history")
public class IncidentHistory {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false, length = 30)
    private String type;

    @Column(name = "from_value", updatable = false)
    private String fromValue;

    @Column(name = "to_value", updatable = false)
    private String toValue;

    @Column(updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IncidentHistory() {
    }

    public IncidentHistory(UUID organizationId, UUID incidentId, UUID actorId, String type, String fromValue,
                           String toValue, String note) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.incidentId = incidentId;
        this.actorId = actorId;
        this.type = type;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.note = note;
    }

    public UUID getActorId() { return actorId; }
    public String getType() { return type; }
    public String getFromValue() { return fromValue; }
    public String getToValue() { return toValue; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
