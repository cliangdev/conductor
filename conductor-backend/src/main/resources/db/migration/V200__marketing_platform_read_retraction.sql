-- Retraction of a marketing platform read. A read is still never deleted, but a wrong one (e.g. recorded from a
-- platform the product no longer uses, which reads a dead 0) can be retracted: it stays in the table and in the
-- history listing with who retracted it and why, and stops counting anywhere: the latest-read lookup skips it, so
-- an older non-retracted read of that metric (if any) takes over. One-way: there is no un-retract. Additive only.
--
-- retracted_at       when it was retracted; NULL for a read that still counts
-- retracted_by       the user who retracted it, if known
-- retraction_reason  why the read was wrong (required, at most 500 characters)

ALTER TABLE marketing_platform_read
    ADD COLUMN retracted_at      TIMESTAMP WITH TIME ZONE NULL,
    ADD COLUMN retracted_by      VARCHAR(36) NULL,
    ADD COLUMN retraction_reason VARCHAR(500) NULL;

ALTER TABLE marketing_platform_read
    ADD CONSTRAINT ck_marketing_platform_read_retraction CHECK (
        (retracted_at IS NULL AND retracted_by IS NULL AND retraction_reason IS NULL)
        OR (retracted_at IS NOT NULL AND retraction_reason IS NOT NULL)
    );

COMMENT ON TABLE marketing_platform_read IS
    'Direct platform reads for the marketing verification ledger. Rows are never deleted; the only change ever made is a one-way retraction (retracted_at, retracted_by, retraction_reason).';
COMMENT ON COLUMN marketing_platform_read.retracted_at IS
    'When the read was retracted as wrong; NULL while it still counts. A retracted read is ignored by the ledger, the overview and alerts.';
