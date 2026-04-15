-- Migrate existing rows to closest equivalent new statuses.
-- Must run in a separate transaction from the ALTER TYPE ADD VALUE in V27,
-- because PostgreSQL does not allow newly-added enum values to be used
-- within the same transaction that added them.
UPDATE issues SET status = 'READY_FOR_DEVELOPMENT' WHERE status = 'APPROVED';
UPDATE issues SET status = 'IN_REVIEW'              WHERE status = 'CHANGES_REQUESTED';
UPDATE issues SET status = 'DONE'                   WHERE status = 'ARCHIVED';
