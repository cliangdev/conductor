-- TikTok consent from an agent session: a consent *request* and an append-only consent *audit trail*.
--
-- Purely additive (two new tables, nothing altered): preview deploys share the production database, so a
-- migration that an older revision cannot live beside would break whichever deploy is older.
--
-- ## tiktok_consent_request
--
-- TikTok requires the creator to consent personally to every Direct Post. An agent can build ten Posts in a
-- minute but cannot consent for anyone, so it asks for a *request*: a short-lived pointer to "these Posts,
-- for me to review in my own browser". The id is opaque and doubles as the MCP elicitationId. It is a
-- pointer, not a secret -- it carries no authority. What protects it is the signed-in session on the
-- consent page plus the owner check: only requested_by may read or complete it.
--
-- work_item_ids is a JSON array of Work Item ids, stored as text like the other small JSON lists in this
-- schema. The ids come from this row, never from URL parameters, so a tampered link cannot widen the set.
-- completed_at is set the first time every listed Post stands VALID, and is only a convenience for pollers.
CREATE TABLE tiktok_consent_request (
    id            VARCHAR(36) PRIMARY KEY,
    project_id    VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- Who asked: the API key's owner. The consent page is bound to this user.
    requested_by  VARCHAR(36) NOT NULL REFERENCES users(id),
    work_item_ids TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_tiktok_consent_request_project ON tiktok_consent_request (project_id, created_at DESC);

-- ## publish_consent_event
--
-- publish_consent (V121) stays the *current state*: one row per Post, rewritten on re-consent, deleted on
-- withdrawal. That cannot answer "who agreed to what, when, through which door" once it has been rewritten,
-- and it records nothing about attempts that were refused. This table is the record: one row per event,
-- never updated, never deleted.
--
-- No foreign keys on work_item_id / user_id / request_id, on purpose: an audit trail must outlive the rows
-- it describes, and must be writable for an attempt by a credential that has no user at all (a project
-- key), which is why user_id is nullable.
--
--   action              CONSENTED | WITHDRAWN | REFUSED_MACHINE_CREDENTIAL | REFUSED_STALE
--   subject_hash        the consent subject hash (PublishConsentService.consentSubjectHash) at the time
--   declaration_json    the per-target choices the person made, as sent
--   declaration_version which wording of the declaration they were shown
--   channel             POST_PAGE | BULK_PAGE
--   credential_type     JWT_HEADER | JWT_COOKIE | USER_API_KEY | PROJECT_API_KEY | RUN_TOKEN | UNKNOWN
CREATE TABLE publish_consent_event (
    id                  VARCHAR(36) PRIMARY KEY,
    project_id          VARCHAR(36),
    work_item_id        VARCHAR(36) NOT NULL,
    user_id             VARCHAR(36),
    action              VARCHAR(40) NOT NULL,
    subject_hash        VARCHAR(64),
    declaration_json    TEXT,
    declaration_version VARCHAR(64),
    channel             VARCHAR(20),
    request_id          VARCHAR(36),
    credential_type     VARCHAR(30),
    ip                  VARCHAR(64),
    user_agent          VARCHAR(512),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_publish_consent_event_work_item ON publish_consent_event (work_item_id, created_at DESC);
