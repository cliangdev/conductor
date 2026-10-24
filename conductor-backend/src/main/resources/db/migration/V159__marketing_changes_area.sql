-- V157 seeded the built-in MARKETING_CHANGES lifecycle under area 'MARKETING_CHANGES', so it routed at
-- /marketing_changes/changes and got its own sidebar group. It belongs in the MARKETING area, beside Posts
-- (/marketing/changes). Move only rows still at the seeded value; a row whose area a user already changed is
-- left alone, snapshots included. Replays are no-ops. The area lives in two places per workflow: the header
-- column + its definition JSON, and every published snapshot's definition JSON.
--
-- Snapshots first: they are keyed off the header's area, which the second statement rewrites.
UPDATE workflow_definition_versions v
SET definition = jsonb_set(v.definition, '{area}', '"MARKETING"')
WHERE v.definition ->> 'id' = 'MARKETING_CHANGES'
  AND v.definition ->> 'area' = 'MARKETING_CHANGES'
  AND EXISTS (
      SELECT 1 FROM workflow_definitions w
      WHERE w.id = v.workflow_definition_id
        AND w.definition ->> 'id' = 'MARKETING_CHANGES'
        AND w.area = 'MARKETING_CHANGES');

UPDATE workflow_definitions
SET area = 'MARKETING',
    definition = jsonb_set(definition, '{area}', '"MARKETING"'),
    updated_at = NOW()
WHERE definition ->> 'id' = 'MARKETING_CHANGES'
  AND area = 'MARKETING_CHANGES';
