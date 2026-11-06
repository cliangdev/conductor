-- Two things the marketing change log needs to take in an outside log without losing what its verdicts meant.
--
-- 1. MARKETING_CHANGES verdict options. The built-in workflow offered worked / did_not_work / inconclusive.
--    Rexipe's change log also says "no effect" and "expected" (a change made for a reason other than lifting a
--    number), and folding those into the three would change their meaning, so the workflow gains no_effect and
--    expected. The definition lives in two places per workflow, the header row and every published snapshot,
--    and a Work Item is pinned to a snapshot: a Work Item on v1 would reject the new values unless the
--    snapshots change too. A hand-edited option list keeps its options and only gains the missing ones.
--    Fresh databases already seed the five options (V157 reads the same JSON), so the guard below matches
--    nothing there; a replay matches nothing either.
--
-- 2. work_item_import_key. Remembers which outside record became which Work Item, so importing the same log
--    again creates nothing new. One row per (workspace, source, record id).

CREATE FUNCTION pg_temp.with_change_verdicts(def jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE AS
$$
SELECT jsonb_set(def, '{fields}', (
    SELECT jsonb_agg(
        CASE WHEN f.field ->> 'key' = 'verdict' AND jsonb_typeof(f.field -> 'options') = 'array'
            THEN jsonb_set(f.field, '{options}',
                CASE WHEN f.field -> 'options' = '["worked","did_not_work","inconclusive"]'::jsonb
                    THEN '["worked","did_not_work","no_effect","expected","inconclusive"]'::jsonb
                    ELSE (f.field -> 'options')
                         || CASE WHEN f.field -> 'options' @> '"no_effect"'::jsonb THEN '[]'::jsonb ELSE '["no_effect"]'::jsonb END
                         || CASE WHEN f.field -> 'options' @> '"expected"'::jsonb THEN '[]'::jsonb ELSE '["expected"]'::jsonb END
                END)
            ELSE f.field
        END
        ORDER BY f.ord)
    FROM jsonb_array_elements(def -> 'fields') WITH ORDINALITY AS f(field, ord)))
$$;

CREATE FUNCTION pg_temp.lacks_change_verdicts(def jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE WHEN jsonb_typeof(def -> 'fields') = 'array' THEN EXISTS (
    SELECT 1 FROM jsonb_array_elements(def -> 'fields') AS f(field)
    WHERE f.field ->> 'key' = 'verdict'
      AND jsonb_typeof(f.field -> 'options') = 'array'
      AND NOT (f.field -> 'options' @> '["no_effect","expected"]'::jsonb))
    ELSE false END
$$;

UPDATE workflow_definition_versions
SET definition = pg_temp.with_change_verdicts(definition)
WHERE definition ->> 'id' = 'MARKETING_CHANGES'
  AND pg_temp.lacks_change_verdicts(definition);

UPDATE workflow_definitions
SET definition = pg_temp.with_change_verdicts(definition),
    updated_at = NOW()
WHERE definition ->> 'id' = 'MARKETING_CHANGES'
  AND pg_temp.lacks_change_verdicts(definition);

DROP FUNCTION pg_temp.with_change_verdicts(jsonb);
DROP FUNCTION pg_temp.lacks_change_verdicts(jsonb);

CREATE TABLE work_item_import_key (
    project_id   VARCHAR(36)  NOT NULL,
    source       VARCHAR(64)  NOT NULL,
    key          VARCHAR(190) NOT NULL,
    work_item_id VARCHAR(36)  NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, source, key)
);

CREATE INDEX idx_work_item_import_key_work_item ON work_item_import_key (work_item_id);

COMMENT ON TABLE work_item_import_key IS
    'Which Work Item an outside record became, per workspace and source; makes re-importing the same log a no-op.';
COMMENT ON COLUMN work_item_import_key.source IS 'Names the outside log (e.g. rexipe); two logs may reuse a record id.';
COMMENT ON COLUMN work_item_import_key.key IS 'The record''s id in that log.';
