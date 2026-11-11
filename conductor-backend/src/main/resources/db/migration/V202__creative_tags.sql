-- Server-side tags on a Creative ({hookType?, angle?, format?, archetype?, hypothesis?}) so performance can be
-- read by hook, angle and so on. '{}' when a Creative has none.
ALTER TABLE creative ADD COLUMN tags jsonb NOT NULL DEFAULT '{}'::jsonb;
