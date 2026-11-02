-- A workspace member holds a SET of roles instead of exactly one. ADMIN implies CREATOR and REVIEWER;
-- an empty set is a read-only viewer. Expand step only: the legacy `role` columns stay (kept in sync to
-- the primary role by the application) because preview deploys share this database with old code. A
-- follow-up migration drops them once this is live. V166 is reserved for an open PR.
ALTER TABLE project_members ADD COLUMN roles varchar(16)[] NOT NULL DEFAULT '{}';
ALTER TABLE project_members ADD CONSTRAINT project_members_roles_valid
    CHECK (roles <@ ARRAY['ADMIN','CREATOR','REVIEWER']::varchar[]);
UPDATE project_members SET roles = ARRAY[role::text];

ALTER TABLE invites ADD COLUMN roles varchar(16)[] NOT NULL DEFAULT '{}';
ALTER TABLE invites ADD CONSTRAINT invites_roles_valid
    CHECK (roles <@ ARRAY['ADMIN','CREATOR','REVIEWER']::varchar[]);
UPDATE invites SET roles = ARRAY[role::text];
