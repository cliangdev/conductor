-- Slideshow style: how organic slideshow slides are styled. A Brand Kit may carry one; a CAROUSEL
-- Creative may record the one it was built with. Additive and nullable only (preview deploys share the
-- prod DB); null means "native look" / "none recorded".
ALTER TABLE brand_kit ADD COLUMN IF NOT EXISTS slideshow_style jsonb;
ALTER TABLE creative ADD COLUMN IF NOT EXISTS slide_style jsonb;
