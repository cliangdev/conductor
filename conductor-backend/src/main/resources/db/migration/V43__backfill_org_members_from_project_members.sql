INSERT INTO org_members (id, org_id, user_id, role, joined_at)
SELECT gen_random_uuid(), p.org_id, pm.user_id, 'MEMBER', NOW()
FROM project_members pm
JOIN projects p ON pm.project_id = p.id
WHERE p.org_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM org_members om
    WHERE om.org_id = p.org_id AND om.user_id = pm.user_id
  );
