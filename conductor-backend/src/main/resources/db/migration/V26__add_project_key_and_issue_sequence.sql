ALTER TABLE projects ADD COLUMN key VARCHAR(10);

WITH raw AS (
    SELECT id,
           UPPER(
               CASE WHEN name ~ '\s'
                   THEN REGEXP_REPLACE(name, '(?:^|\s+)(\S)\S*', '\1', 'g')
                   ELSE LEFT(REGEXP_REPLACE(name, '[^A-Za-z0-9]', '', 'g'), 4)
               END
           ) AS raw_candidate
    FROM projects
),
ranked AS (
    SELECT id, raw_candidate,
           ROW_NUMBER() OVER (PARTITION BY raw_candidate ORDER BY id) AS rn
    FROM raw
)
UPDATE projects
SET key = CASE WHEN ranked.rn = 1 THEN ranked.raw_candidate
               ELSE ranked.raw_candidate || (ranked.rn - 1)::text
          END
FROM ranked
WHERE projects.id = ranked.id;

UPDATE projects SET key = 'P' || id WHERE key IS NULL OR key = '';

ALTER TABLE projects ALTER COLUMN key SET NOT NULL;
CREATE UNIQUE INDEX uq_projects_key ON projects(key);

ALTER TABLE issues ADD COLUMN sequence_number INTEGER;

WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY created_at, id) AS rn
    FROM issues
)
UPDATE issues SET sequence_number = numbered.rn
FROM numbered WHERE issues.id = numbered.id;

ALTER TABLE issues ALTER COLUMN sequence_number SET NOT NULL;
ALTER TABLE issues ADD CONSTRAINT uq_issues_project_sequence UNIQUE (project_id, sequence_number);
CREATE INDEX idx_issues_project_sequence ON issues(project_id, sequence_number);
