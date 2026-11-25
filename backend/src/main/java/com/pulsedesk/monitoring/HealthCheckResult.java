package com.pulsedesk.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_check_results")
public class HealthCheckResult {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_time_ms", nullable = false)
    private long responseTimeMs;

    private String error;

    protected HealthCheckResult() {
    }

    public HealthCheckResult(UUID organizationId, UUID configId, Instant checkedAt, ProbeOutcome outcome) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.configId = configId;
        this.checkedAt = checkedAt;
        this.success = outcome.success();
        this.statusCode = outcome.statusCode();
        this.responseTimeMs = outcome.responseTimeMs();
        this.error = outcome.error();
    }

    public UUID getId() { return id; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isSuccess() { return success; }
    public Integer getStatusCode() { return statusCode; }
    public long getResponseTimeMs() { return responseTimeMs; }
    public String getError() { return error; }
}
