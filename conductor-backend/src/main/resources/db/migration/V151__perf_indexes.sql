-- Additive, idempotent indexes for hot list/detail queries (perf PR 2). IF NOT EXISTS keeps this safe to
-- re-run and safe for preview deploys that share the prod database.
--
-- Audited and deliberately NOT added because an existing index already leads with the column:
--   documents(work_item_id), work_item_reviewers(work_item_id), reviews(work_item_id)  -> UNIQUE(issue_id, ...)
--   project_docs(project_id), project_docs(folder_id), doc_versions(doc_id), doc_comments(doc_id),
--   doc_comment_replies(comment_id), knowledge_page_revisions(page_id)  -> existing idx / UNIQUE(page_id, version)
--   creative_render(creative_id), creative_render_frame(render_id), creative_experiment(parent_creative_id)
--   work_items(project_id, workflow), assets(work_item_id), assets(media_id)

-- The comments table has had no index on its Work Item since V7 (column renamed issue_id -> work_item_id in V75).
CREATE INDEX IF NOT EXISTS idx_comments_work_item_id ON comments (work_item_id);
CREATE INDEX IF NOT EXISTS idx_comments_document_id ON comments (document_id);
CREATE INDEX IF NOT EXISTS idx_comment_replies_comment_id ON comment_replies (comment_id);

-- Assets linked to a Creative render frame (Media usage counts, post asset previews).
CREATE INDEX IF NOT EXISTS idx_assets_creative_frame_id ON assets (creative_frame_id) WHERE creative_frame_id IS NOT NULL;

-- Frames reusing a Media item's stored object (CreativePhotoService usage lookup).
CREATE INDEX IF NOT EXISTS idx_creative_render_frame_gcs_path ON creative_render_frame (gcs_path);

-- The knowledge log reads the newest revisions across a project.
CREATE INDEX IF NOT EXISTS idx_knowledge_page_revisions_created ON knowledge_page_revisions (created_at DESC);

-- Latest SUCCEEDED non-preview render per Creative (batched creatives list).
CREATE INDEX IF NOT EXISTS idx_creative_render_creative_state ON creative_render (creative_id, state, requested_at DESC);
