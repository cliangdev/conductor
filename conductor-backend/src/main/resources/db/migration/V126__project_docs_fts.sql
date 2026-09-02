-- Project-docs search was a LIKE scan over title/content (no ranking, no index) -- swaps in the same
-- weighted-tsvector generated-column + GIN pattern V90 established for knowledge_pages. Title-hit rows
-- rank ahead of body-only hits (weight A vs C); there is no separate "description" column on project_docs
-- (unlike knowledge_pages), so this is a two-weight vector, not three.

ALTER TABLE project_docs ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(content, '')), 'C')
) STORED;

CREATE INDEX idx_project_docs_search ON project_docs USING GIN (search_vector);
