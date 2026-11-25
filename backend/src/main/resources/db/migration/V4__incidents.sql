CREATE TABLE incidents (
    id                         UUID PRIMARY KEY,
    organization_id            UUID          NOT NULL REFERENCES organizations (id),
    service_id                 UUID,
    team_id                    UUID,
    title                      VARCHAR(200)  NOT NULL,
    description                VARCHAR(2000),
    status                     VARCHAR(20)   NOT NULL
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    priority                   VARCHAR(10)   NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    source                     VARCHAR(20)   NOT NULL CHECK (source IN ('MANUAL', 'HEALTH_CHECK')),
    assignee_id                UUID,
    created_by_id              UUID,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    acknowledged_at            TIMESTAMPTZ,
    resolved_at                TIMESTAMPTZ,
    closed_at                  TIMESTAMPTZ,
    -- SLA snapshot, fixed at creation from the priority policy of that moment
    sla_response_minutes       INT           NOT NULL,
    sla_resolution_minutes     INT           NOT NULL,
    sla_response_due_at        TIMESTAMPTZ   NOT NULL,
    sla_resolution_due_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_incident_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_incident_service FOREIGN KEY (service_id, organization_id)
        REFERENCES services (id, organization_id),
    CONSTRAINT fk_incident_team FOREIGN KEY (team_id, organization_id)
        REFERENCES teams (id, organization_id),
    CONSTRAINT fk_incident_assignee FOREIGN KEY (assignee_id, organization_id)
        REFERENCES organization_members (id, organization_id),
    CONSTRAINT fk_incident_creator FOREIGN KEY (created_by_id, organization_id)
        REFERENCES organization_members (id, organization_id)
);

-- Deduplication guard: at most one unresolved health-check incident per service, even under races.
CREATE UNIQUE INDEX uq_incident_active_health_check ON incidents (service_id)
    WHERE source = 'HEALTH_CHECK' AND status NOT IN ('RESOLVED', 'CLOSED');

CREATE INDEX idx_incidents_org_status ON incidents (organization_id, status, created_at DESC);
CREATE INDEX idx_incidents_service ON incidents (service_id);

CREATE TABLE incident_comments (
    id               UUID PRIMARY KEY,
    organization_id  UUID          NOT NULL,
    incident_id      UUID          NOT NULL,
    author_id        UUID          NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_comment_incident FOREIGN KEY (incident_id, organization_id)
        REFERENCES incidents (id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_author FOREIGN KEY (author_id, organization_id)
        REFERENCES organization_members (id, organization_id)
);

CREATE INDEX idx_comments_incident ON incident_comments (incident_id, created_at);

CREATE TABLE incident_history (
    id               UUID PRIMARY KEY,
    organization_id  UUID          NOT NULL,
    incident_id      UUID          NOT NULL,
    actor_id         UUID,
    type             VARCHAR(30)   NOT NULL,
    from_value       VARCHAR(100),
    to_value         VARCHAR(100),
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_history_incident FOREIGN KEY (incident_id, organization_id)
        REFERENCES incidents (id, organization_id) ON DELETE CASCADE
);

CREATE INDEX idx_history_incident ON incident_history (incident_id, created_at);
