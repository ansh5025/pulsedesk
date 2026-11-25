CREATE TABLE services (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id),
    team_id          UUID,
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_id_org UNIQUE (id, organization_id),
    CONSTRAINT uq_service_name_org UNIQUE (organization_id, name),
    CONSTRAINT fk_service_team FOREIGN KEY (team_id, organization_id)
        REFERENCES teams (id, organization_id)
);

CREATE TABLE health_check_configs (
    id                      UUID PRIMARY KEY,
    organization_id         UUID         NOT NULL,
    service_id              UUID         NOT NULL UNIQUE,
    url                     VARCHAR(500) NOT NULL,
    expected_status         INT          NOT NULL DEFAULT 200 CHECK (expected_status BETWEEN 100 AND 599),
    interval_seconds        INT          NOT NULL DEFAULT 60 CHECK (interval_seconds BETWEEN 30 AND 3600),
    timeout_ms              INT          NOT NULL DEFAULT 5000 CHECK (timeout_ms BETWEEN 1000 AND 30000),
    failure_threshold       INT          NOT NULL DEFAULT 3 CHECK (failure_threshold BETWEEN 1 AND 10),
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    status                  VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN', 'UP', 'DOWN')),
    consecutive_failures    INT          NOT NULL DEFAULT 0,
    last_checked_at         TIMESTAMPTZ,
    last_status_change_at   TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_check_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_check_service FOREIGN KEY (service_id, organization_id)
        REFERENCES services (id, organization_id)
);

CREATE INDEX idx_checks_due ON health_check_configs (enabled, last_checked_at);

CREATE TABLE health_check_results (
    id                UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL,
    config_id         UUID        NOT NULL,
    checked_at        TIMESTAMPTZ NOT NULL,
    success           BOOLEAN     NOT NULL,
    status_code       INT,
    response_time_ms  BIGINT      NOT NULL,
    error             VARCHAR(300),
    CONSTRAINT fk_result_check FOREIGN KEY (config_id, organization_id)
        REFERENCES health_check_configs (id, organization_id) ON DELETE CASCADE
);

CREATE INDEX idx_results_config_time ON health_check_results (config_id, checked_at DESC);
CREATE INDEX idx_results_time ON health_check_results (checked_at);
