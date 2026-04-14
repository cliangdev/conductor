ALTER TYPE issue_status ADD VALUE IF NOT EXISTS 'READY_FOR_DEVELOPMENT';
ALTER TYPE issue_status ADD VALUE IF NOT EXISTS 'IN_PROGRESS';
ALTER TYPE issue_status ADD VALUE IF NOT EXISTS 'CODE_REVIEW';
ALTER TYPE issue_status ADD VALUE IF NOT EXISTS 'DONE';
ALTER TYPE issue_status ADD VALUE IF NOT EXISTS 'CLOSED';

-- Migrate existing rows to closest equivalent new statuses
UPDATE issues SET status = 'READY_FOR_DEVELOPMENT' WHERE status = 'APPROVED';
UPDATE issues SET status = 'IN_REVIEW'              WHERE status = 'CHANGES_REQUESTED';
UPDATE issues SET status = 'DONE'                   WHERE status = 'ARCHIVED';
