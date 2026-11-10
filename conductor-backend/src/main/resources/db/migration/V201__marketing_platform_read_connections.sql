-- A platform read is a hand-recorded figure checked against a channel's connected account. Record which
-- connections that was, so a read made against an account that has since been disconnected stops being compared
-- with whatever account is connected next.
ALTER TABLE marketing_platform_read ADD COLUMN connection_ids VARCHAR(36)[] NULL;

COMMENT ON COLUMN marketing_platform_read.connection_ids IS
    'The channel''s ACTIVE connection ids when the read was recorded. A read counts only while at least one of them is still an ACTIVE connection of the project. NULL = recorded before this column existed: it always counts.';
