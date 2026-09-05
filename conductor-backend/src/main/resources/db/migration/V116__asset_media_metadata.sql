-- COND-23 T5.1: per-target media validation runs at the approval gate, never at fire time. To decide
-- whether Instagram will accept an image's aspect ratio, or whether a video fits a TikTok creator's own
-- length cap, the approval path needs the file's intrinsic shape -- and it must have it without touching
-- object storage while a human waits on a status change. So the shape is captured once, at upload confirm,
-- and stored on the row.
--
-- All three columns are NULLABLE on purpose. Null means "not known", never "fine": image dimensions are
-- derived server-side from the uploaded bytes and video duration is declared by the uploading client, so
-- either can legitimately be absent (an unreadable format, an older row, a client that sent nothing).
-- MediaTargetValidator blocks approval on a rule it cannot evaluate rather than assuming the file passes.
ALTER TABLE assets
    ADD COLUMN width            INTEGER,
    ADD COLUMN height           INTEGER,
    -- NUMERIC, not INTEGER: platform length caps are whole seconds but real media is not, and truncating a
    -- 60.4s clip to 60 would pass a TikTok cap the platform itself would then reject at publish.
    ADD COLUMN duration_seconds NUMERIC(12, 3),
    -- A recorded dimension or duration must be a real measurement. Zero or negative is a bug upstream, and
    -- silently storing it would let a nonsense value satisfy a rule. NULL stays allowed by every check.
    ADD CONSTRAINT chk_assets_media_width CHECK (width IS NULL OR width > 0),
    ADD CONSTRAINT chk_assets_media_height CHECK (height IS NULL OR height > 0),
    ADD CONSTRAINT chk_assets_media_duration CHECK (duration_seconds IS NULL OR duration_seconds > 0);
