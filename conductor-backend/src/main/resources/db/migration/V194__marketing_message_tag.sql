-- Message tags for the promise ledger (RE-99 T7.2). Additive only.
--
-- A tag says "this target makes this promise": a creative, a custom product page, an Apple Ads keyword, a paywall
-- headline or a Post carries a short message (for example 'save-from-tiktok'). The promise ledger joins every
-- message to installs, intent picks and trial and day-7 outcomes at read time; nothing derived is stored here.
--
--   message     stored trimmed, lower-cased and whitespace-collapsed, so one message is one string. It is matched
--               case-insensitively to the product backend's `message:<tag>` rows.
--   target_kind CREATIVE | PRODUCT_PAGE | KEYWORD | PAYWALL_HEADLINE | POST.
--   target_ref  the creative id, product page id, keyword id, the paywall headline text or the Post's Work Item id.
--
-- A target carries one message: the unique key is the target, so tagging it again moves it.

CREATE TABLE marketing_message_tag (
    id          VARCHAR(36)  PRIMARY KEY,
    project_id  VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    message     VARCHAR(120) NOT NULL,
    target_kind VARCHAR(24)  NOT NULL,
    target_ref  VARCHAR(200) NOT NULL,
    label       VARCHAR(200),
    created_by  VARCHAR(36),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_message_tag_target UNIQUE (project_id, target_kind, target_ref),
    CONSTRAINT ck_marketing_message_tag_kind
        CHECK (target_kind IN ('CREATIVE', 'PRODUCT_PAGE', 'KEYWORD', 'PAYWALL_HEADLINE', 'POST'))
);

CREATE INDEX idx_marketing_message_tag_message ON marketing_message_tag (project_id, message);

COMMENT ON TABLE marketing_message_tag IS
    'The message a creative, product page, keyword, paywall headline or Post makes; one message per target.';
