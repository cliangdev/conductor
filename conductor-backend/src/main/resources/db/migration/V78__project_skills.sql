-- Project-scoped skill registry (#240 §3 genericity gap): let a user register their own bindable Claude Code
-- skill ids for a project so a non-engineering Workflow (e.g. marketing) can bind a custom skill from a
-- transition step and Publish without editing the classpath skill-registry.json + redeploying. Layers on top
-- of the built-in registry the same way DB-authored Workflows layer on the built-in Statechart.

CREATE TABLE project_skills (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    skill_id    VARCHAR(128) NOT NULL,
    label       VARCHAR(255),
    description  TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_skill UNIQUE (project_id, skill_id)
);

CREATE INDEX idx_project_skills_project ON project_skills(project_id);
