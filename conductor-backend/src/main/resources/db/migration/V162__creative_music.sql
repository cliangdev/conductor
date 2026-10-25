-- Music on CAROUSEL and CLIP Creatives.
--
-- creative.slideshow: {slideDurations?: number[], zoom?: boolean} for a CAROUSEL that renders a companion
-- slideshow video (only meaningful when audio.source = "track"). Null = defaults.
-- creative_render_frame.audio_media_id: the library AUDIO item mixed into a video frame, so a track's
-- Post count is exact (a frame of a Creative that merely references the track is not necessarily a video
-- with that sound). Ids are VARCHAR(36) throughout this schema.
ALTER TABLE creative ADD COLUMN slideshow jsonb;
ALTER TABLE creative_render_frame ADD COLUMN audio_media_id VARCHAR(36);
CREATE INDEX idx_creative_render_frame_audio_media ON creative_render_frame (audio_media_id)
    WHERE audio_media_id IS NOT NULL;
