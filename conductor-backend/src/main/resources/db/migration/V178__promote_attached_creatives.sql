-- Creatives attached to a Post before attach started promoting them (#692) never left DRAFT. Promote every
-- DRAFT Creative that has a rendered frame on a Post. version is left alone (as CreativeRepository.
-- updateStateKeepingVersion does) so renders already attached keep reading as current. No readiness checks:
-- SQL can't evaluate them, and a Creative that reached a Post was already good enough to attach. ARCHIVED
-- Creatives are untouched.

UPDATE creative c
SET state = 'READY', updated_at = now()
WHERE c.state = 'DRAFT'
  AND EXISTS (
    SELECT 1 FROM assets a
    JOIN creative_render_frame f ON f.id = a.creative_frame_id
    WHERE f.creative_id = c.id);
