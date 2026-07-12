-- Knowledge Center domain core (Phase 1): a unified ingestion inbox (knowledge_sources) feeding an
-- LLM-maintained wiki in OKF format (markdown pages with YAML frontmatter, path = identity). Pages are
-- versioned with full-content revisions for provenance (which sources produced/changed a page) and a
-- link graph resolved at write time. No controllers/scheduler yet -- those land in later phases.

CREATE TABLE knowledge_sources (
    id                 VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id         VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_type        VARCHAR(100) NOT NULL,
    source_ref         VARCHAR(512),
    title              VARCHAR(255),
    content_type       VARCHAR(100),
    payload            TEXT NULL,
    payload_uri        VARCHAR(512) NULL,
    metadata           JSONB,
    origin             JSONB,
    occurred_at        TIMESTAMP WITH TIME ZONE,
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    dedup_key          VARCHAR(128) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts           INT NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMP WITH TIME ZONE NULL,
    processing_run_id  VARCHAR(36) NULL,
    error_message      TEXT,
    UNIQUE (project_id, dedup_key)
);

CREATE INDEX idx_knowledge_sources_status ON knowledge_sources (status, next_attempt_at);
CREATE INDEX idx_knowledge_sources_project_status ON knowledge_sources (project_id, status);

CREATE TABLE knowledge_pages (
    id             VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id     VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    path           VARCHAR(512) NOT NULL,
    page_type      VARCHAR(64) NOT NULL,
    title          VARCHAR(255),
    description    TEXT,
    frontmatter    JSONB NOT NULL,
    body           TEXT NOT NULL,
    content_hash   VARCHAR(64) NOT NULL,
    version        INT NOT NULL DEFAULT 1,
    deleted        BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    search_vector  tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
        setweight(to_tsvector('english', body), 'C')
    ) STORED,
    UNIQUE (project_id, path)
);

CREATE INDEX idx_knowledge_pages_search ON knowledge_pages USING GIN (search_vector);
CREATE INDEX idx_knowledge_pages_project_type ON knowledge_pages (project_id, page_type);

CREATE TABLE knowledge_page_revisions (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    page_id       VARCHAR(36) NOT NULL REFERENCES knowledge_pages(id) ON DELETE CASCADE,
    version       INT NOT NULL,
    frontmatter   JSONB,
    body          TEXT,
    content_hash  VARCHAR(64),
    change_kind   VARCHAR(10) NOT NULL,
    actor         JSONB,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (page_id, version)
);

CREATE TABLE knowledge_revision_sources (
    revision_id  VARCHAR(36) NOT NULL REFERENCES knowledge_page_revisions(id) ON DELETE CASCADE,
    source_id    VARCHAR(36) NOT NULL REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    PRIMARY KEY (revision_id, source_id)
);

CREATE INDEX idx_knowledge_revision_sources_source ON knowledge_revision_sources (source_id);

CREATE TABLE knowledge_links (
    id                VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id        VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    from_page_id      VARCHAR(36) NOT NULL REFERENCES knowledge_pages(id) ON DELETE CASCADE,
    to_path           VARCHAR(512) NOT NULL,
    resolved_page_id  VARCHAR(36) NULL REFERENCES knowledge_pages(id) ON DELETE SET NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_knowledge_links_project_to_path ON knowledge_links (project_id, to_path);
CREATE INDEX idx_knowledge_links_resolved_page ON knowledge_links (resolved_page_id);
