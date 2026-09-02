-- Work Items were human-only: created_by was a NOT NULL FK to users, so a machine principal (an
-- addressable agent creating a Work Item via the coordinator tool surface, see
-- CoordinatorToolProvider#create_work_item) could not author one at all.
--
-- Same fix as V109 for project_docs: the FK goes nullable and a created_by_label column carries the
-- machine actor's identity (e.g. 'Agent (ceo)'). The CHECK constraint is what keeps a byline from ever
-- rendering blank -- at least one form of attribution is always present. (Which of the two a row uses
-- is decided by ProjectActor, which never sets both.)

ALTER TABLE work_items
    ALTER COLUMN created_by DROP NOT NULL,
    ADD COLUMN created_by_label VARCHAR(255),
    ADD CONSTRAINT chk_work_items_created_attribution
        CHECK (created_by IS NOT NULL OR created_by_label IS NOT NULL);
