package com.pulsedesk.invitation;

import com.pulsedesk.membership.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(length = 254)
    private String email;
    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Invitation() {
    }

    public Invitation(UUID organizationId, String fullName, Role role, UUID teamId, String tokenHash,
                      UUID invitedBy, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.fullName = fullName;
        this.role = role;
        this.teamId = teamId;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public UUID getTeamId() { return teamId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void markAccepted() { this.acceptedAt = Instant.now(); }
}
