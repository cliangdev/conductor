-- Drop the check constraint that references selection columns
ALTER TABLE comments DROP CONSTRAINT IF EXISTS chk_comment_anchor;

-- Remove selection-based anchor columns
ALTER TABLE comments DROP COLUMN IF EXISTS selection_start;
ALTER TABLE comments DROP COLUMN IF EXISTS selection_length;

-- Add new line-anchoring columns
ALTER TABLE comments ADD COLUMN IF NOT EXISTS quoted_text TEXT NOT NULL DEFAULT '';
ALTER TABLE comments ADD COLUMN IF NOT EXISTS line_stale BOOLEAN NOT NULL DEFAULT FALSE;

-- Make line_number NOT NULL with a default of 0
ALTER TABLE comments ALTER COLUMN line_number SET DEFAULT 0;
UPDATE comments SET line_number = 0 WHERE line_number IS NULL;
ALTER TABLE comments ALTER COLUMN line_number SET NOT NULL;
