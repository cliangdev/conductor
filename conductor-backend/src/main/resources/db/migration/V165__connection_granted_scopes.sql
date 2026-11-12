-- The OAuth scopes a connection was last authorized with, so the UI can tell when a connector now
-- asks for more than the grant covers. Null for connections authorized before this column existed:
-- an unknown grant is never reported as missing permissions.
ALTER TABLE connection ADD COLUMN granted_scopes JSONB;
