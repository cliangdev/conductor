-- A project's designated Claude runtime target: which named RuntimeTarget (if any) the "cloud-run"
-- runs-on value should resolve to for this project, instead of the operator's builtin env-configured
-- target. Nullable -- ON DELETE SET NULL means deleting the target silently falls the project back to
-- builtin rather than leaving a dangling reference.
ALTER TABLE project_settings
    ADD COLUMN claude_runtime_target_id VARCHAR(36) REFERENCES runtime_targets(id) ON DELETE SET NULL;
