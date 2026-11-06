-- AI-assistant visibility checks (RE-99 T7.4): once a week a fixed set of questions ("best app to save recipes
-- from TikTok") is put to each AI assistant the workspace has a key for, and whether the brand is named is
-- recorded. Additive only.
--
-- Computed on read, never stored: the trend, the latest result per assistant and the list of assistants skipped
-- for want of a credential. An assistant with no credential has NO row here: absence means "not checked", never
-- "not named". A provider error also writes no row.

-- The fixed question set. Questions dropped from the set are marked inactive, so their history stays readable.
CREATE TABLE marketing_ai_question (
    id         VARCHAR(36)  PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    question   VARCHAR(400) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_marketing_ai_question_project ON marketing_ai_question (project_id, active);

-- One reading per question, assistant and ISO week (week_start is that week's Monday, UTC); the unique key makes
-- a repeated run in the same week a no-op. assistant is the model provider id (claude, openai, ...) and model is
-- the model asked. rank_in_answer is the 1-based position of the first list item that names the brand when the
-- answer is a numbered or bulleted list, NULL when the brand is not named or the answer is not a list.
-- excerpt is about 200 characters either side of the first mention, or the start of the answer when not named.
CREATE TABLE marketing_ai_visibility_check (
    id             VARCHAR(36)  PRIMARY KEY,
    project_id     VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    question_id    VARCHAR(36)  NOT NULL REFERENCES marketing_ai_question(id) ON DELETE CASCADE,
    assistant      VARCHAR(32)  NOT NULL,
    model          VARCHAR(120),
    week_start     DATE         NOT NULL,
    named          BOOLEAN      NOT NULL,
    rank_in_answer INT          CHECK (rank_in_answer IS NULL OR rank_in_answer >= 1),
    excerpt        VARCHAR(600),
    checked_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_ai_visibility_check UNIQUE (question_id, assistant, week_start)
);

CREATE INDEX idx_marketing_ai_visibility_check_project_week
    ON marketing_ai_visibility_check (project_id, week_start);

-- The words that count as naming the brand, a JSON array of strings. No row means the project's own name.
CREATE TABLE marketing_ai_settings (
    project_id  VARCHAR(36) PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    brand_terms JSONB       NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
