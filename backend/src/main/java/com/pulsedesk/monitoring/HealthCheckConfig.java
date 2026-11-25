package com.pulsedesk.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_check_configs")
public class HealthCheckConfig {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(nullable = false)
    private String url;

    @Column(name = "expected_status", nullable = false)
    private int expectedStatus;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HealthStatus status = HealthStatus.UNKNOWN;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_status_change_at")
    private Instant lastStatusChangeAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected HealthCheckConfig() {
    }

    public HealthCheckConfig(UUID organizationId, UUID serviceId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.serviceId = serviceId;
    }

    /** Changing the target resets the observed state so old failures do not carry over. */
    public void apply(String url, int expectedStatus, int intervalSeconds, int timeoutMs, int failureThreshold,
                      boolean enabled) {
        if (!url.equals(this.url)) {
            this.status = HealthStatus.UNKNOWN;
            this.consecutiveFailures = 0;
            this.lastCheckedAt = null;
            this.lastStatusChangeAt = null;
        }
        this.url = url;
        this.expectedStatus = expectedStatus;
        this.intervalSeconds = intervalSeconds;
        this.timeoutMs = timeoutMs;
        this.failureThreshold = failureThreshold;
        this.enabled = enabled;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getServiceId() { return serviceId; }
    public String getUrl() { return url; }
    public int getExpectedStatus() { return expectedStatus; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getFailureThreshold() { return failureThreshold; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public HealthStatus getStatus() { return status; }
    public void setStatus(HealthStatus status) { this.status = status; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getLastStatusChangeAt() { return lastStatusChangeAt; }
    public void setLastStatusChangeAt(Instant lastStatusChangeAt) { this.lastStatusChangeAt = lastStatusChangeAt; }
}
