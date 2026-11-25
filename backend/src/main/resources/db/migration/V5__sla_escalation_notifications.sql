ALTER TABLE incidents
    ADD COLUMN escalation_level        INT         NOT NULL DEFAULT 0,
    ADD COLUMN response_escalated_at   TIMESTAMPTZ,
    ADD COLUMN resolution_escalated_at TIMESTAMPTZ;

CREATE INDEX idx_incidents_response_due ON incidents (sla_response_due_at)
    WHERE acknowledged_at IS NULL AND response_escalated_at IS NULL AND status IN ('OPEN');
CREATE INDEX idx_incidents_resolution_due ON incidents (sla_resolution_due_at)
    WHERE resolution_escalated_at IS NULL AND status IN ('OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS');

CREATE TABLE notifications (
    id               UUID PRIMARY KEY,
    organization_id  UUID          NOT NULL,
    recipient_id     UUID          NOT NULL,
    incident_id      UUID,
    type             VARCHAR(30)   NOT NULL,
    title            VARCHAR(200)  NOT NULL,
    message          VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at          TIMESTAMPTZ,
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id, organization_id)
        REFERENCES organization_members (id, organization_id),
    CONSTRAINT fk_notification_incident FOREIGN KEY (incident_id, organization_id)
        REFERENCES incidents (id, organization_id) ON DELETE CASCADE
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;
