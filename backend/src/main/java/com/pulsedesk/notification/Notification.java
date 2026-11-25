package com.pulsedesk.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "incident_id", updatable = false)
    private UUID incidentId;

    @Column(nullable = false, updatable = false, length = 30)
    private String type;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    public Notification(UUID organizationId, UUID recipientId, UUID incidentId, String type, String title,
                        String message) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.recipientId = recipientId;
        this.incidentId = incidentId;
        this.type = type;
        this.title = title;
        this.message = message;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
}
