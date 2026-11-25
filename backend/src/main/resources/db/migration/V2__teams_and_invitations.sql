CREATE TABLE teams (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id),
    name             VARCHAR(100) NOT NULL,
    team_lead_id     UUID UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_id_org UNIQUE (id, organization_id),
    CONSTRAINT uq_team_name_org UNIQUE (organization_id, name),
    -- lead must be a member of the same organization
    CONSTRAINT fk_team_lead FOREIGN KEY (team_lead_id, organization_id)
        REFERENCES organization_members (id, organization_id)
);

-- A member belongs to at most one team, and only a team of the same organization.
ALTER TABLE organization_members ADD COLUMN team_id UUID;
ALTER TABLE organization_members ADD CONSTRAINT fk_member_team
    FOREIGN KEY (team_id, organization_id) REFERENCES teams (id, organization_id);

CREATE INDEX idx_members_team ON organization_members (team_id);

CREATE TABLE invitations (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id),
    full_name        VARCHAR(150) NOT NULL,
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('MANAGER', 'TEAM_LEAD', 'DEVELOPER')),
    team_id          UUID,
    token_hash       VARCHAR(64)  NOT NULL UNIQUE,
    invited_by       UUID         NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    accepted_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_invitation_team FOREIGN KEY (team_id, organization_id)
        REFERENCES teams (id, organization_id),
    CONSTRAINT fk_invitation_inviter FOREIGN KEY (invited_by, organization_id)
        REFERENCES organization_members (id, organization_id)
);

CREATE INDEX idx_invitations_org ON invitations (organization_id);
