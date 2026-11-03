-- A workspace member holds a SET of roles instead of exactly one. ADMIN implies CREATOR and REVIEWER;
-- an empty set is a read-only viewer. Expand step only: the legacy `role` columns stay (kept in sync to
-- the primary role by the application) because preview deploys share this database with old code. A
-- follow-up migration drops them once this is live. The number must be re-checked right before merge.
ALTER TABLE project_members ADD COLUMN roles varchar(16)[];
ALTER TABLE project_members ADD CONSTRAINT project_members_roles_valid
    CHECK (roles <@ ARRAY['ADMIN','CREATOR','REVIEWER']::varchar[]);
UPDATE project_members SET roles = ARRAY[role::text];

ALTER TABLE invites ADD COLUMN roles varchar(16)[];
ALTER TABLE invites ADD CONSTRAINT invites_roles_valid
    CHECK (roles <@ ARRAY['ADMIN','CREATOR','REVIEWER']::varchar[]);
UPDATE invites SET roles = ARRAY[role::text];

-- `roles` is deliberately NULLABLE with NO default during the expand phase (the CHECK passes on NULL). Old
-- code on the shared DB (preview deploys) never names the column, so its INSERTs leave it NULL and the trigger
-- below fills it from `role`; its role-only UPDATEs are mirrored the same way. Without that, a member or invite
-- written by old code would read as an empty-set Viewer (a workspace created meanwhile would have no admin).
-- New code always writes `roles` explicitly (including '{}' for a Viewer), so the trigger leaves it alone.
-- The contract migration that drops `role` also sets `roles` NOT NULL and drops this trigger and function.
CREATE FUNCTION sync_roles_from_role() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.roles IS NULL AND NEW.role IS NOT NULL THEN
            NEW.roles := ARRAY[NEW.role::text];
        END IF;
    ELSIF NEW.role IS DISTINCT FROM OLD.role AND NEW.roles IS NOT DISTINCT FROM OLD.roles THEN
        NEW.roles := ARRAY[NEW.role::text];
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_members_sync_roles BEFORE INSERT OR UPDATE ON project_members
    FOR EACH ROW EXECUTE FUNCTION sync_roles_from_role();
CREATE TRIGGER invites_sync_roles BEFORE INSERT OR UPDATE ON invites
    FOR EACH ROW EXECUTE FUNCTION sync_roles_from_role();
