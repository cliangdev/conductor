ALTER TABLE project_api_keys ADD COLUMN IF NOT EXISTS key_value VARCHAR(255);
ALTER TABLE project_api_keys DROP COLUMN IF EXISTS key_hash;
