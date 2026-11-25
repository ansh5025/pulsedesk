CREATE TABLE organizations (
    id          UUID PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    slug        VARCHAR(50)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    username       VARCHAR(120) NOT NULL UNIQUE,
    full_name      VARCHAR(150) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One user belongs to exactly one organization (UNIQUE user_id).
-- team_id is added in Phase 2 together with the teams table.
CREATE TABLE organization_members (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organizations (id),
    user_id          UUID        NOT NULL UNIQUE REFERENCES users (id),
    role             VARCHAR(20) NOT NULL CHECK (role IN ('ORG_ADMIN', 'MANAGER', 'TEAM_LEAD', 'DEVELOPER')),
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_id_org UNIQUE (id, organization_id)
);

CREATE INDEX idx_members_org ON organization_members (organization_id);
