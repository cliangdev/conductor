-- COND-24: video creatives. One migration for both PR1 (clip creatives + video/audio media library) and
-- PR2 (motion creatives) so PR2 needs no further migration — see the video-creatives contract.
--
-- PR1 uses creative_photo.media_kind/duration_seconds/has_audio/poster_gcs_path/codec, creative.kind/
-- clip_media, and creative_render_frame.duration_seconds/has_audio/poster_gcs_path. PR2 will use
-- creative.motion/audio, added here but untouched by any PR1 code path.

ALTER TABLE creative_photo
    ADD COLUMN media_kind       VARCHAR(8) NOT NULL DEFAULT 'IMAGE',
    ADD COLUMN duration_seconds NUMERIC(10,3),
    ADD COLUMN has_audio        BOOLEAN,
    ADD COLUMN poster_gcs_path  VARCHAR,
    ADD COLUMN codec            VARCHAR;

COMMENT ON COLUMN creative_photo.media_kind IS
    'IMAGE | VIDEO | AUDIO (COND-24 PR1). width/height are required for IMAGE/VIDEO and unused for AUDIO.';
COMMENT ON COLUMN creative_photo.duration_seconds IS
    'Running time in seconds, required for VIDEO/AUDIO, client-declared like an Asset''s own duration '
    '(no server-side media pipeline here — see CLAUDE.md).';
COMMENT ON COLUMN creative_photo.has_audio IS 'Whether a VIDEO carries an audio track; null for IMAGE/AUDIO.';
COMMENT ON COLUMN creative_photo.poster_gcs_path IS
    'A JPEG poster frame for a VIDEO, minted via the poster mint/confirm endpoints; null until set.';
COMMENT ON COLUMN creative_photo.codec IS 'Advisory client-declared codec hint (e.g. h264); never validated.';

ALTER TABLE creative
    ADD COLUMN kind       VARCHAR(8) NOT NULL DEFAULT 'STILL',
    ADD COLUMN clip_media JSONB,
    ADD COLUMN motion     JSONB,
    ADD COLUMN audio      JSONB;

COMMENT ON COLUMN creative.kind IS
    'STILL | MOTION | CLIP (COND-24). STILL is today''s Creative; CLIP is a finished video used as-is, '
    'one file for all placements or one per placement; MOTION (PR2) is a branded animated video.';
COMMENT ON COLUMN creative.clip_media IS
    '{"default": mediaId, "<placementKey>": mediaId, ...} for a CLIP creative — "default" is used for '
    'every placement the map does not otherwise name. Each mediaId names a VIDEO row in creative_photo.';
COMMENT ON COLUMN creative.motion IS 'PR2: the branded animation timeline for a MOTION creative. Unused by PR1.';
COMMENT ON COLUMN creative.audio IS
    'PR2: {source: clip|track|none, trackId?, volume?, fadeOutSec?} for a MOTION creative. Unused by PR1.';

ALTER TABLE creative_render_frame
    ADD COLUMN duration_seconds NUMERIC(10,3),
    ADD COLUMN has_audio        BOOLEAN,
    ADD COLUMN poster_gcs_path  VARCHAR;

COMMENT ON COLUMN creative_render_frame.duration_seconds IS
    'Video frame running time, copied from the source media at render assembly time (COND-24 PR1); null for an image frame.';
COMMENT ON COLUMN creative_render_frame.has_audio IS
    'Whether a video frame carries an audio track, copied from the source media; null for an image frame.';
COMMENT ON COLUMN creative_render_frame.poster_gcs_path IS
    'A copy of the source media''s poster JPEG under this render''s own path, for a video frame''s posterUrl; null otherwise.';
