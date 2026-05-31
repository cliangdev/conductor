CREATE TABLE doc_folders (
    id         VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    parent_id  VARCHAR(36) REFERENCES doc_folders(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Sibling uniqueness for non-root folders (parent_id IS NOT NULL)
CREATE UNIQUE INDEX idx_doc_folders_unique_name_with_parent
    ON doc_folders (project_id, parent_id, name)
    WHERE parent_id IS NOT NULL;

-- Sibling uniqueness for root folders (parent_id IS NULL)
CREATE UNIQUE INDEX idx_doc_folders_unique_name_at_root
    ON doc_folders (project_id, name)
    WHERE parent_id IS NULL;

CREATE INDEX idx_doc_folders_project_id ON doc_folders (project_id);
CREATE INDEX idx_doc_folders_parent_id  ON doc_folders (parent_id);

CREATE TABLE project_docs (
    id         VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    folder_id  VARCHAR(36) REFERENCES doc_folders(id) ON DELETE SET NULL,
    title      VARCHAR(500) NOT NULL,
    content    TEXT,
    created_by VARCHAR(36) NOT NULL REFERENCES users(id),
    updated_by VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Title uniqueness within a folder (folder_id IS NOT NULL)
CREATE UNIQUE INDEX idx_project_docs_unique_title_in_folder
    ON project_docs (project_id, folder_id, title)
    WHERE folder_id IS NOT NULL;

-- Title uniqueness at root level (folder_id IS NULL)
CREATE UNIQUE INDEX idx_project_docs_unique_title_at_root
    ON project_docs (project_id, title)
    WHERE folder_id IS NULL;

CREATE INDEX idx_project_docs_project_id ON project_docs (project_id);
CREATE INDEX idx_project_docs_folder_id  ON project_docs (folder_id);
