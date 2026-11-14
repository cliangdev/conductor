-- Built-in lifecycle Workflows gain an optional one-line `description` (shown under the list page title), and the
-- built-in CREATORS Workflow is renamed in the UI from the noun "Creator" to "Partner" (the page was mistaken for
-- Creatives, and "Creator" is also a member role). New workspaces get both from the seed JSON; this brings existing
-- ones along, the same way V159 moved an area: header column JSON + every published snapshot's JSON.
--
-- Only rows still exactly as seeded are touched: the header must be named after its slug (the seeder's name), carry
-- the built-in noun, and not already have a description. A workspace's customised copy (renamed noun, own
-- description, reused slug under another name) is left alone. Internal ids (CREATORS, CREATOR, status ids) do not
-- change. Replays are no-ops. Backward compatible with the code on main: it ignores the new key, and the old noun
-- still resolves because the route is derived from the noun in the same row.
--
-- Snapshots first: they are matched through the header, whose noun/description the second statement rewrites.
CREATE TEMP TABLE built_in_workflow_copy (slug text PRIMARY KEY, noun text NOT NULL, new_noun text, description text NOT NULL);

INSERT INTO built_in_workflow_copy (slug, noun, new_noun, description) VALUES
 ('ENGINEERING', 'Issue', NULL, 'Specs, feature requests and bug reports, from draft through review and development to done.'),
 ('MARKETING', 'Post', NULL, 'Social posts for your accounts. Each goes through review, then publishes at its set time, or you post it yourself when no account is connected.'),
 ('MARKETING_CHANGES', 'Change', NULL, 'Changes to your listing, ads, pricing or product, recorded so you can review their effect later.'),
 ('TASK', 'Task', NULL, 'Smaller pieces of work broken out of a larger item.'),
 ('CREATORS', 'Creator', 'Partner', 'Outside creators and partners you brief or pay to post about your product. Track each one from first contact to payout.');

UPDATE workflow_definition_versions v
SET definition = v.definition
        || jsonb_build_object('noun', COALESCE(c.new_noun, c.noun))
        || CASE WHEN v.definition ? 'description' THEN '{}'::jsonb ELSE jsonb_build_object('description', c.description) END
FROM built_in_workflow_copy c
WHERE v.definition ->> 'id' = c.slug
  AND v.definition ->> 'noun' = c.noun
  AND EXISTS (
      SELECT 1 FROM workflow_definitions w
      WHERE w.id = v.workflow_definition_id
        AND w.name = c.slug
        AND w.definition ->> 'id' = c.slug
        AND w.definition ->> 'noun' = c.noun
        AND NOT (w.definition ? 'description'));

UPDATE workflow_definitions w
SET definition = w.definition
        || jsonb_build_object('noun', COALESCE(c.new_noun, c.noun), 'description', c.description),
    updated_at = NOW()
FROM built_in_workflow_copy c
WHERE w.definition ->> 'id' = c.slug
  AND w.name = c.slug
  AND w.definition ->> 'noun' = c.noun
  AND NOT (w.definition ? 'description');

DROP TABLE built_in_workflow_copy;
