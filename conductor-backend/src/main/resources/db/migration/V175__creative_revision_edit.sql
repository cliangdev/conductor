-- Editable slideshow text, T2: a revision can now be an edit that is rendered by the render Workflow before it is
-- applied. base_version is the Creative's version the edit was made against (the apply refuses a Creative that moved
-- on); alt_text_flags lists the 1-based slides whose alt text quotes replaced text (null until applied).
-- Additive and nullable only (preview deploys share the prod DB).
ALTER TABLE creative_revision ADD COLUMN IF NOT EXISTS base_version INT;
ALTER TABLE creative_revision ADD COLUMN IF NOT EXISTS alt_text_flags jsonb;

-- At most one PENDING revision per Creative: a second edit while one is rendering is refused (409), and this
-- index is the backstop for two requests racing past that check.
CREATE UNIQUE INDEX IF NOT EXISTS uq_creative_revision_one_pending
    ON creative_revision (creative_id) WHERE status = 'PENDING';
